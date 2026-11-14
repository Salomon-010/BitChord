// fork (ForkExclusiveOutput): WASAPI exclusive mode — the samples go to the device as they are, no
// Windows mixer, resampler, volume or effects in between.
//
// Included by window_jni.cpp inside its anonymous namespace, after the shared-mode sink, so it shares
// that sink's lock, device resolution and COM scope. Nothing here is upstream's.
//
// Kotlin decides everything that can be decided without a device (ForkExclusiveOutput.kt):
// - which formats to ask about: [fork_ex_probe] answers, for each (rate, container bits, valid bits),
//   whether IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE) accepts it. Exclusive mode has no
//   "closest match": each candidate is a yes or a no;
// - which one to open, and the bytes: it dithers and packs the samples itself.
// A refusal anywhere (device in use, exclusive mode not allowed in Windows' settings, a format the
// driver accepted at the probe but not at Initialize, a device that stops) is returned as a failure,
// and Kotlin falls back to shared mode.
//
// Event-driven, as Microsoft's exclusive-mode sample does it: AUDCLNT_STREAMFLAGS_EVENTCALLBACK, the
// buffer one device period long (aligned when the driver asks), and a render thread at "Pro Audio"
// priority that, at each event, hands the device exactly one buffer: the next frames of a ring the
// playback thread fills, and silence for whatever the ring lacks. GetCurrentPadding is never polled
// (a first, timer-driven version did, and the owner's USB headset played noise and then stopped
// consuming). Nothing waits for ever: the playback thread's write gives up after FORK_EX_STALL_MS of
// a device that takes nothing, and the end-of-track drain after the ring's length plus a margin.

const REFERENCE_TIME FORK_EX_MIN_PERIOD = 100'000; // 10 ms: some USB drivers' default is shorter than they cope with
const int FORK_EX_RING_MS = 500;                   // what the playback thread may run ahead of the device
const DWORD FORK_EX_STALL_MS = 2'000;              // a device that takes nothing this long has stopped
const DWORD FORK_EX_DRAIN_MARGIN_MS = 500;

IAudioClient* g_fork_ex_client = nullptr;
IAudioRenderClient* g_fork_ex_render = nullptr;
HANDLE g_fork_ex_event = nullptr;
std::thread g_fork_ex_thread;
UINT32 g_fork_ex_buffer_frames = 0;
UINT32 g_fork_ex_block = 0; // bytes per frame
UINT32 g_fork_ex_rate = 0;
std::wstring g_fork_ex_name;
std::wstring g_fork_ex_error;

// The client while a stream is open: the render thread's GetBuffer/ReleaseBuffer and the playback
// thread's Start/Stop/Reset never overlap. Taken before g_fork_ex_ring_mutex when both are needed.
std::mutex g_fork_ex_client_mutex;
std::atomic<bool> g_fork_ex_started{false};        // playback wants it running (not paused)
std::atomic<bool> g_fork_ex_stop{false};           // the render thread should end
std::atomic<bool> g_fork_ex_failed{false};         // the device stopped or went away

// The ring, in device bytes, and how much of it is audio not yet handed over.
std::mutex g_fork_ex_ring_mutex;
std::condition_variable g_fork_ex_ring_space;      // signalled when the render thread takes frames
std::vector<uint8_t> g_fork_ex_ring;
size_t g_fork_ex_ring_frames = 0;                  // capacity in frames
size_t g_fork_ex_ring_read = 0;                    // frame index
size_t g_fork_ex_ring_filled = 0;                  // frames waiting
std::atomic<uint64_t> g_fork_ex_consumed{0};       // audio frames handed to the device (not silence)

void fork_ex_set_error(const wchar_t* operation, HRESULT result) {
    wchar_t code[32] = {};
    swprintf(code, 31, L"0x%08lX", static_cast<unsigned long>(result));
    g_fork_ex_error = operation;
    g_fork_ex_error += L" failed (";
    g_fork_ex_error += code;
    g_fork_ex_error += L")";
}

/** The format as WAVEFORMATEXTENSIBLE: always, in exclusive mode, so the valid bits are stated. */
WAVEFORMATEXTENSIBLE fork_ex_format(int rate, int channels, int container_bits, int valid_bits) {
    static const GUID subtype_pcm = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
    WAVEFORMATEXTENSIBLE format{};
    format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    format.Format.nChannels = static_cast<WORD>(channels);
    format.Format.nSamplesPerSec = static_cast<DWORD>(rate);
    format.Format.wBitsPerSample = static_cast<WORD>(container_bits);
    format.Format.nBlockAlign = static_cast<WORD>(channels * container_bits / 8);
    format.Format.nAvgBytesPerSec = format.Format.nSamplesPerSec * format.Format.nBlockAlign;
    format.Format.cbSize = static_cast<WORD>(sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX));
    format.Samples.wValidBitsPerSample = static_cast<WORD>(valid_bits);
    format.dwChannelMask = channels == 1 ? SPEAKER_FRONT_CENTER : (SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT);
    format.SubFormat = subtype_pcm;
    return format;
}

/**
 * One buffer for the device at [target]: the ring's next frames, silence for the rest. Called with
 * the client lock held; takes the ring lock.
 */
void fork_ex_fill(BYTE* target, UINT32 frames) {
    size_t copied = 0;
    {
        std::lock_guard<std::mutex> ring(g_fork_ex_ring_mutex);
        const size_t wanted = std::min<size_t>(frames, g_fork_ex_ring_filled);
        while (copied < wanted) {
            const size_t run = std::min(wanted - copied, g_fork_ex_ring_frames - g_fork_ex_ring_read);
            std::memcpy(target + copied * g_fork_ex_block, g_fork_ex_ring.data() + g_fork_ex_ring_read * g_fork_ex_block, run * g_fork_ex_block);
            g_fork_ex_ring_read = (g_fork_ex_ring_read + run) % g_fork_ex_ring_frames;
            copied += run;
        }
        g_fork_ex_ring_filled -= copied;
    }
    if (copied < frames) std::memset(target + copied * g_fork_ex_block, 0, (frames - copied) * g_fork_ex_block);
    g_fork_ex_consumed += copied;
    g_fork_ex_ring_space.notify_all();
}

/** Hands the device one buffer; false when the device failed. Client lock held. */
bool fork_ex_render_one_locked() {
    BYTE* target = nullptr;
    HRESULT status = g_fork_ex_render->GetBuffer(g_fork_ex_buffer_frames, &target);
    if (FAILED(status) || target == nullptr) {
        fork_ex_set_error(L"Acquiring the WASAPI exclusive buffer", status);
        return false;
    }
    fork_ex_fill(target, g_fork_ex_buffer_frames);
    status = g_fork_ex_render->ReleaseBuffer(g_fork_ex_buffer_frames, 0);
    if (FAILED(status)) {
        fork_ex_set_error(L"Submitting the WASAPI exclusive buffer", status);
        return false;
    }
    return true;
}

/** The render thread: one buffer per event, at "Pro Audio" priority, until told to stop or the device fails. */
void fork_ex_render_thread() {
    ComScope com;
    DWORD task = 0;
    HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task);
    while (!g_fork_ex_stop) {
        // A timeout only so a stop is seen while the stream is paused (no events then).
        const DWORD woke = WaitForSingleObject(g_fork_ex_event, 200);
        if (g_fork_ex_stop) break;
        if (woke != WAIT_OBJECT_0) continue;
        std::lock_guard<std::mutex> client(g_fork_ex_client_mutex);
        if (g_fork_ex_client == nullptr || !g_fork_ex_started) continue;
        if (!fork_ex_render_one_locked()) {
            g_fork_ex_failed = true;
            g_fork_ex_ring_space.notify_all();
            break;
        }
    }
    if (mmcss != nullptr) AvRevertMmThreadCharacteristics(mmcss);
}

/** Stops and joins the render thread, then lets the client go. g_audio_mutex held. */
void fork_ex_release_locked() {
    g_fork_ex_stop = true;
    if (g_fork_ex_event != nullptr) SetEvent(g_fork_ex_event);
    if (g_fork_ex_thread.joinable()) g_fork_ex_thread.join();
    {
        std::lock_guard<std::mutex> client(g_fork_ex_client_mutex);
        if (g_fork_ex_client != nullptr) g_fork_ex_client->Stop();
        if (g_fork_ex_render != nullptr) {
            g_fork_ex_render->Release();
            g_fork_ex_render = nullptr;
        }
        if (g_fork_ex_client != nullptr) {
            g_fork_ex_client->Release();
            g_fork_ex_client = nullptr;
        }
        g_fork_ex_started = false;
    }
    if (g_fork_ex_event != nullptr) {
        CloseHandle(g_fork_ex_event);
        g_fork_ex_event = nullptr;
    }
    {
        std::lock_guard<std::mutex> ring(g_fork_ex_ring_mutex);
        g_fork_ex_ring.clear();
        g_fork_ex_ring_frames = 0;
        g_fork_ex_ring_read = 0;
        g_fork_ex_ring_filled = 0;
    }
    g_fork_ex_ring_space.notify_all();
    g_fork_ex_buffer_frames = 0;
    g_fork_ex_block = 0;
    g_fork_ex_rate = 0;
    g_fork_ex_consumed = 0;
    g_fork_ex_failed = false;
    g_fork_ex_stop = false;
    g_fork_ex_name.clear();
}

uint64_t fork_ex_played() {
    return g_fork_ex_consumed.load();
}

/**
 * For each of the [count] candidates, whether the endpoint [wanted] resolves to takes it in exclusive
 * mode. Empty (with the error set) when there is no endpoint or no client to ask.
 */
std::vector<bool> fork_ex_probe(
    const std::wstring& wanted, int channels, const int* rates, const int* containers, const int* valids, int count) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    g_fork_ex_error.clear();
    ComScope com;
    if (!com.ready()) {
        fork_ex_set_error(L"COM initialization", com.result);
        return {};
    }
    IMMDevice* device = resolve_device_locked(wanted);
    if (device == nullptr) {
        g_fork_ex_error = L"Windows has no audio endpoint to play on";
        return {};
    }
    IAudioClient* client = nullptr;
    const HRESULT activated = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&client));
    device->Release();
    if (FAILED(activated) || client == nullptr) {
        fork_ex_set_error(L"Opening the audio endpoint", activated);
        return {};
    }
    std::vector<bool> accepted(static_cast<size_t>(count), false);
    for (int index = 0; index < count; ++index) {
        WAVEFORMATEXTENSIBLE format = fork_ex_format(rates[index], channels, containers[index], valids[index]);
        accepted[static_cast<size_t>(index)] =
            client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, &format.Format, nullptr) == S_OK;
    }
    client->Release();
    return accepted;
}

/**
 * Initializes [client] exclusive and event-driven in [format], buffer = period = the device's default
 * period (at least 10 ms); once more at the aligned size when the driver answers
 * AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED (Microsoft's documented retry: the aligned frame count, a new
 * client, the duration that matches it).
 */
HRESULT fork_ex_initialize(IMMDevice* device, IAudioClient** client, const WAVEFORMATEXTENSIBLE& format) {
    REFERENCE_TIME period = 0;
    if (FAILED((*client)->GetDevicePeriod(&period, nullptr)) || period < FORK_EX_MIN_PERIOD) period = FORK_EX_MIN_PERIOD;
    const DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    HRESULT status = (*client)->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, flags, period, period, &format.Format, nullptr);
    if (status != AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) return status;
    UINT32 frames = 0;
    (*client)->GetBufferSize(&frames);
    (*client)->Release();
    *client = nullptr;
    status = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(client));
    if (FAILED(status) || *client == nullptr) return FAILED(status) ? status : E_FAIL;
    const REFERENCE_TIME aligned = static_cast<REFERENCE_TIME>(
        10'000'000.0 * frames / format.Format.nSamplesPerSec + 0.5);
    return (*client)->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, flags, aligned, aligned, &format.Format, nullptr);
}

/**
 * Opens [wanted] exclusive in the given format, closing this process's shared-mode and Spatial Sound
 * lines first (a shared stream on the device would refuse exclusive mode with DEVICE_IN_USE), primes
 * the device with one buffer of silence, starts it and its render thread. The ring's size in bytes,
 * or 0 with the error set.
 */
int fork_ex_open(const std::wstring& wanted, int rate, int channels, int container_bits, int valid_bits) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    fork_ex_release_locked();
    release_audio_locked();
    fork_spatial_close();
    g_fork_ex_error.clear();
    ComScope com;
    if (!com.ready()) {
        fork_ex_set_error(L"COM initialization", com.result);
        return 0;
    }
    IMMDevice* device = resolve_device_locked(wanted);
    if (device == nullptr) {
        g_fork_ex_error = L"Windows has no audio endpoint to play on";
        return 0;
    }
    IAudioClient* client = nullptr;
    HRESULT status = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&client));
    if (FAILED(status) || client == nullptr) {
        fork_ex_set_error(L"Opening the audio endpoint", status);
        device->Release();
        return 0;
    }
    const WAVEFORMATEXTENSIBLE format = fork_ex_format(rate, channels, container_bits, valid_bits);
    status = fork_ex_initialize(device, &client, format);
    HANDLE event = nullptr;
    UINT32 frames = 0;
    IAudioRenderClient* render = nullptr;
    if (SUCCEEDED(status)) {
        event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        status = event == nullptr ? HRESULT_FROM_WIN32(GetLastError()) : client->SetEventHandle(event);
    }
    if (SUCCEEDED(status)) status = client->GetBufferSize(&frames);
    if (SUCCEEDED(status)) status = client->GetService(__uuidof(IAudioRenderClient), reinterpret_cast<void**>(&render));
    if (FAILED(status) || render == nullptr) {
        fork_ex_set_error(L"Starting WASAPI exclusive mode", status);
        if (render != nullptr) render->Release();
        if (client != nullptr) client->Release();
        if (event != nullptr) CloseHandle(event);
        device->Release();
        return 0;
    }
    {
        std::lock_guard<std::mutex> ring(g_fork_ex_ring_mutex);
        g_fork_ex_ring_frames = std::max<size_t>(static_cast<size_t>(rate) * FORK_EX_RING_MS / 1000, frames * 2u);
        g_fork_ex_ring.assign(g_fork_ex_ring_frames * format.Format.nBlockAlign, 0);
        g_fork_ex_ring_read = 0;
        g_fork_ex_ring_filled = 0;
    }
    g_fork_ex_block = format.Format.nBlockAlign;
    g_fork_ex_buffer_frames = frames;
    g_fork_ex_rate = static_cast<UINT32>(rate);
    g_fork_ex_consumed = 0;
    g_fork_ex_failed = false;
    g_fork_ex_stop = false;
    g_fork_ex_event = event;
    g_fork_ex_name = property_string(device, PKEY_Device_FriendlyName);
    device->Release();
    {
        std::lock_guard<std::mutex> lock(g_fork_ex_client_mutex);
        g_fork_ex_client = client;
        g_fork_ex_render = render;
        // Microsoft's sample: the first buffer before Start, so the device has something defined to play.
        status = fork_ex_render_one_locked() ? g_fork_ex_client->Start() : E_FAIL;
        g_fork_ex_started = SUCCEEDED(status);
    }
    if (FAILED(status)) {
        if (g_fork_ex_error.empty()) fork_ex_set_error(L"Starting the WASAPI exclusive stream", status);
        fork_ex_release_locked();
        return 0;
    }
    g_fork_ex_thread = std::thread(fork_ex_render_thread);
    return static_cast<int>(g_fork_ex_ring_frames * g_fork_ex_block);
}

/**
 * Puts [frames] whole frames of [bytes] (already in the device's format) in the ring, waiting while it
 * is full. The frames taken: fewer when paused with a full ring; -1 when the device failed or took
 * nothing for FORK_EX_STALL_MS, so Kotlin falls back to shared mode. Never waits longer than that.
 */
int fork_ex_write(const uint8_t* bytes, int frames) {
    if (bytes == nullptr || frames <= 0) return 0;
    if (g_fork_ex_block == 0 || g_fork_ex_failed) return -1;
    std::unique_lock<std::mutex> ring(g_fork_ex_ring_mutex);
    int done = 0;
    ULONGLONG waiting_since = 0;
    while (done < frames) {
        if (g_fork_ex_failed || g_fork_ex_ring_frames == 0) return done > 0 ? done : -1;
        const size_t space = g_fork_ex_ring_frames - g_fork_ex_ring_filled;
        if (space == 0) {
            if (!g_fork_ex_started) return done; // paused: nothing will take them now
            const ULONGLONG now = GetTickCount64();
            if (waiting_since == 0) waiting_since = now;
            if (now - waiting_since >= FORK_EX_STALL_MS) {
                g_fork_ex_error = L"The device stopped taking audio in exclusive mode";
                g_fork_ex_failed = true;
                return done > 0 ? done : -1;
            }
            g_fork_ex_ring_space.wait_for(ring, std::chrono::milliseconds(20));
            continue;
        }
        waiting_since = 0;
        const size_t write_at = (g_fork_ex_ring_read + g_fork_ex_ring_filled) % g_fork_ex_ring_frames;
        const size_t run = std::min<size_t>({space, static_cast<size_t>(frames - done), g_fork_ex_ring_frames - write_at});
        std::memcpy(g_fork_ex_ring.data() + write_at * g_fork_ex_block, bytes + static_cast<size_t>(done) * g_fork_ex_block, run * g_fork_ex_block);
        g_fork_ex_ring_filled += run;
        done += static_cast<int>(run);
    }
    return done;
}

void fork_ex_pause(bool paused) {
    std::lock_guard<std::mutex> client(g_fork_ex_client_mutex);
    ComScope com;
    if (g_fork_ex_client == nullptr || g_fork_ex_started == !paused) return;
    if (paused) {
        g_fork_ex_client->Stop();
        g_fork_ex_started = false;
        g_fork_ex_ring_space.notify_all();
        return;
    }
    // Stopped, the device kept its last buffer: Reset, then one buffer from the ring before Start.
    g_fork_ex_client->Reset();
    if (fork_ex_render_one_locked() && SUCCEEDED(g_fork_ex_client->Start())) {
        g_fork_ex_started = true;
    } else {
        g_fork_ex_failed = true;
    }
}

/** Throws away what is queued (a seek): the ring emptied, the device restarted on silence. */
void fork_ex_flush() {
    std::lock_guard<std::mutex> client(g_fork_ex_client_mutex);
    ComScope com;
    if (g_fork_ex_client == nullptr) return;
    {
        std::lock_guard<std::mutex> ring(g_fork_ex_ring_mutex);
        g_fork_ex_ring_read = 0;
        g_fork_ex_ring_filled = 0;
    }
    g_fork_ex_ring_space.notify_all();
    if (!g_fork_ex_started) return;
    g_fork_ex_client->Stop();
    g_fork_ex_client->Reset();
    if (!fork_ex_render_one_locked() || FAILED(g_fork_ex_client->Start())) g_fork_ex_failed = true;
}

/**
 * Waits for the ring to play out (the end of the last track), then one more device buffer; never
 * longer than the ring's length plus FORK_EX_DRAIN_MARGIN_MS, and not at all while paused or failed.
 */
void fork_ex_drain() {
    if (g_fork_ex_block == 0 || g_fork_ex_rate == 0) return;
    const ULONGLONG deadline = GetTickCount64() + FORK_EX_RING_MS + FORK_EX_DRAIN_MARGIN_MS;
    std::unique_lock<std::mutex> ring(g_fork_ex_ring_mutex);
    while (g_fork_ex_ring_filled > 0 && g_fork_ex_started && !g_fork_ex_failed && GetTickCount64() < deadline) {
        g_fork_ex_ring_space.wait_for(ring, std::chrono::milliseconds(20));
    }
    const DWORD tail_ms = static_cast<DWORD>(1000ull * g_fork_ex_buffer_frames / g_fork_ex_rate) + 1;
    ring.unlock();
    if (g_fork_ex_started && !g_fork_ex_failed) Sleep(std::min<DWORD>(tail_ms, FORK_EX_DRAIN_MARGIN_MS));
}

void fork_ex_close() {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    fork_ex_release_locked();
}
