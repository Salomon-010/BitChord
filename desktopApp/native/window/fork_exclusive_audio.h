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
// - which one to open, and the bytes: it dithers and packs the samples itself, and [fork_ex_write]
//   only copies whole frames into the device's buffer.
// A refusal anywhere (device in use, exclusive mode not allowed in Windows' settings, a format the
// driver accepted at the probe but not at Initialize) is returned as a failure, and Kotlin falls back
// to shared mode.
//
// Timer-driven, not event-driven: the playback thread pushes as the shared sink does, waiting on
// GetCurrentPadding (which an exclusive stream without EVENTCALLBACK reports the same way), with a
// 200 ms buffer. The stream starts once it holds audio, so nothing undefined plays before the first
// write; while paused it is stopped.

IAudioClient* g_fork_ex_client = nullptr;
IAudioRenderClient* g_fork_ex_render = nullptr;
UINT32 g_fork_ex_buffer_frames = 0;
UINT32 g_fork_ex_block = 0; // bytes per frame
UINT32 g_fork_ex_rate = 0;
bool g_fork_ex_started = false; // playback wants it running
bool g_fork_ex_running = false; // the client is actually started
uint64_t g_fork_ex_submitted = 0;
uint64_t g_fork_ex_played_offset = 0;
std::wstring g_fork_ex_name;
std::wstring g_fork_ex_error;

const REFERENCE_TIME FORK_EX_BUFFER = 2'000'000; // 200 ms, as the shared sink

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

void fork_ex_release_locked() {
    if (g_fork_ex_client != nullptr) g_fork_ex_client->Stop();
    if (g_fork_ex_render != nullptr) {
        g_fork_ex_render->Release();
        g_fork_ex_render = nullptr;
    }
    if (g_fork_ex_client != nullptr) {
        g_fork_ex_client->Release();
        g_fork_ex_client = nullptr;
    }
    g_fork_ex_buffer_frames = 0;
    g_fork_ex_block = 0;
    g_fork_ex_rate = 0;
    g_fork_ex_started = false;
    g_fork_ex_running = false;
    g_fork_ex_submitted = 0;
    g_fork_ex_played_offset = 0;
    g_fork_ex_name.clear();
}

uint64_t fork_ex_played_locked() {
    if (g_fork_ex_client == nullptr) return g_fork_ex_played_offset;
    UINT32 padding = 0;
    if (FAILED(g_fork_ex_client->GetCurrentPadding(&padding))) padding = 0;
    const uint64_t queued = std::min<uint64_t>(padding, g_fork_ex_submitted);
    return g_fork_ex_played_offset + g_fork_ex_submitted - queued;
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

/** Initializes [client] exclusive in [format], once more at the aligned size when the driver asks. */
HRESULT fork_ex_initialize(IMMDevice* device, IAudioClient** client, const WAVEFORMATEXTENSIBLE& format) {
    HRESULT status = (*client)->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, 0, FORK_EX_BUFFER, 0, &format.Format, nullptr);
    if (status != AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) return status;
    // The documented retry: the aligned size in frames, a new client, the duration that matches it.
    UINT32 frames = 0;
    (*client)->GetBufferSize(&frames);
    (*client)->Release();
    *client = nullptr;
    status = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(client));
    if (FAILED(status) || *client == nullptr) return FAILED(status) ? status : E_FAIL;
    const REFERENCE_TIME aligned = static_cast<REFERENCE_TIME>(
        10'000'000.0 * frames / format.Format.nSamplesPerSec + 0.5);
    return (*client)->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, 0, aligned, 0, &format.Format, nullptr);
}

/**
 * Opens [wanted] exclusive in the given format, closing this process's shared-mode and Spatial Sound
 * lines first (a shared stream on the device would refuse exclusive mode with DEVICE_IN_USE). The
 * device buffer in bytes, or 0 with the error set.
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
    if (FAILED(status)) {
        fork_ex_set_error(L"Starting WASAPI exclusive mode", status);
        if (client != nullptr) client->Release();
        device->Release();
        return 0;
    }
    UINT32 frames = 0;
    IAudioRenderClient* render = nullptr;
    status = client->GetBufferSize(&frames);
    if (SUCCEEDED(status)) status = client->GetService(__uuidof(IAudioRenderClient), reinterpret_cast<void**>(&render));
    if (FAILED(status) || render == nullptr) {
        fork_ex_set_error(L"Starting the WASAPI exclusive render stream", status);
        client->Release();
        device->Release();
        return 0;
    }
    g_fork_ex_client = client;
    g_fork_ex_render = render;
    g_fork_ex_buffer_frames = frames;
    g_fork_ex_block = format.Format.nBlockAlign;
    g_fork_ex_rate = static_cast<UINT32>(rate);
    g_fork_ex_started = true;
    g_fork_ex_running = false;
    g_fork_ex_name = property_string(device, PKEY_Device_FriendlyName);
    device->Release();
    return static_cast<int>(frames * g_fork_ex_block);
}

/**
 * Copies [frames] whole frames of [bytes] (already in the device's format) into the buffer, waiting
 * while it is full. The frames taken; -1 when the device went away or failed (the error set), so
 * Kotlin can fall back to shared mode.
 */
int fork_ex_write(const uint8_t* bytes, int frames) {
    if (bytes == nullptr || frames <= 0) return 0;
    std::unique_lock<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (!com.ready() || g_fork_ex_client == nullptr || g_fork_ex_block == 0) return -1;
    int done = 0;
    while (done < frames) {
        UINT32 padding = 0;
        HRESULT status = g_fork_ex_client->GetCurrentPadding(&padding);
        if (FAILED(status)) {
            fork_ex_set_error(L"Reading the WASAPI exclusive buffer state", status);
            return done > 0 ? done : -1;
        }
        const UINT32 available = padding < g_fork_ex_buffer_frames ? g_fork_ex_buffer_frames - padding : 0;
        if (available == 0) {
            // Full: playing out. Paused with a full buffer there is nothing to wait for; give the frames back.
            if (!g_fork_ex_started) return done;
            guard.unlock();
            Sleep(2);
            guard.lock();
            if (g_fork_ex_client == nullptr) return done > 0 ? done : -1; // closed meanwhile
            continue;
        }
        const UINT32 count = std::min<UINT32>(available, static_cast<UINT32>(frames - done));
        BYTE* target = nullptr;
        status = g_fork_ex_render->GetBuffer(count, &target);
        if (FAILED(status) || target == nullptr) {
            fork_ex_set_error(L"Acquiring the WASAPI exclusive buffer", status);
            return done > 0 ? done : -1;
        }
        std::memcpy(target, bytes + static_cast<size_t>(done) * g_fork_ex_block, static_cast<size_t>(count) * g_fork_ex_block);
        status = g_fork_ex_render->ReleaseBuffer(count, 0);
        if (FAILED(status)) {
            fork_ex_set_error(L"Submitting the WASAPI exclusive buffer", status);
            return done > 0 ? done : -1;
        }
        done += static_cast<int>(count);
        g_fork_ex_submitted += count;
        if (g_fork_ex_started && !g_fork_ex_running) {
            status = g_fork_ex_client->Start();
            if (FAILED(status)) {
                fork_ex_set_error(L"Starting the WASAPI exclusive stream", status);
                return -1;
            }
            g_fork_ex_running = true;
        }
    }
    return done;
}

void fork_ex_pause(bool paused) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (g_fork_ex_client == nullptr) return;
    if (paused) {
        g_fork_ex_started = false;
        if (g_fork_ex_running) g_fork_ex_client->Stop();
        g_fork_ex_running = false;
        return;
    }
    g_fork_ex_started = true;
    UINT32 padding = 0;
    // Started again only with something to play; otherwise the next write starts it.
    if (SUCCEEDED(g_fork_ex_client->GetCurrentPadding(&padding)) && padding > 0 && SUCCEEDED(g_fork_ex_client->Start())) {
        g_fork_ex_running = true;
    }
}

void fork_ex_flush() {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (g_fork_ex_client == nullptr) return;
    g_fork_ex_client->Stop();
    g_fork_ex_played_offset = fork_ex_played_locked();
    g_fork_ex_submitted = 0;
    g_fork_ex_client->Reset();
    g_fork_ex_running = false;
}

void fork_ex_drain() {
    while (true) {
        {
            std::lock_guard<std::mutex> guard(g_audio_mutex);
            ComScope com;
            if (g_fork_ex_client == nullptr || !g_fork_ex_running) return;
            UINT32 padding = 0;
            if (FAILED(g_fork_ex_client->GetCurrentPadding(&padding)) || padding == 0) return;
        }
        Sleep(2);
    }
}

void fork_ex_close() {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    fork_ex_release_locked();
}
