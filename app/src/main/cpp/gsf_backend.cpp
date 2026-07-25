#include "gsf_backend.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <limits>
#include <vector>

#include "psflib.h"

#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba-util/vfs.h>

namespace {

constexpr uint8_t GSF_PSF_VERSION = 0x22;
constexpr size_t GBA_MAX_ROM_SIZE = 32 * 1024 * 1024;

struct LoaderState {
  bool entry_set = false;
  uint32_t entry = 0;
  std::vector<uint8_t> rom;
};

struct BackendState {
  mCore *core = nullptr;
  std::vector<uint8_t> rom;
  std::vector<uint8_t> initial_state;
  uint64_t current_sample = 0;
  uint64_t total_samples = 0;
  int sample_rate = 44100;
  GsfTags tags;
};

BackendState gState;

void discard_mgba_log(mLogger *, int, mLogLevel, const char *, va_list) {}

mLogger gSilentLogger = {discard_mgba_log, nullptr};

uint32_t read_le32(const uint8_t *p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

size_t next_power_of_two(size_t value) {
  if (value <= 1)
    return 1;
  --value;
  for (size_t shift = 1; shift < sizeof(value) * 8; shift <<= 1)
    value |= value >> shift;
  return value + 1;
}

int load_gsf_section(void *context, const uint8_t *exe, size_t exe_size,
                     const uint8_t *, size_t) {
  if (!context || !exe || exe_size < 12)
    return -1;

  auto *state = static_cast<LoaderState *>(context);
  const uint32_t entry = read_le32(exe);
  const size_t offset = read_le32(exe + 4) & 0x01FFFFFFu;
  const size_t declared_size = read_le32(exe + 8);
  const size_t available_size = exe_size - 12;
  if (declared_size > available_size || offset > GBA_MAX_ROM_SIZE ||
      declared_size > GBA_MAX_ROM_SIZE - offset) {
    return -1;
  }

  if (!state->entry_set) {
    state->entry = entry;
    state->entry_set = true;
  }

  const size_t required = offset + declared_size;
  if (required > state->rom.size()) {
    const size_t capacity =
        std::min(GBA_MAX_ROM_SIZE, next_power_of_two(required));
    state->rom.resize(capacity, 0);
  }
  std::copy_n(exe + 12, declared_size, state->rom.begin() + offset);
  return 0;
}

uint64_t parse_time_ms(const char *value) {
  if (!value || !*value)
    return 0;
  char *end = nullptr;
  errno = 0;
  const double seconds = std::strtod(value, &end);
  if (!errno && end && *end == '\0' && seconds >= 0.0)
    return static_cast<uint64_t>(seconds * 1000.0 + 0.5);

  unsigned hours = 0;
  unsigned minutes = 0;
  double final_seconds = 0.0;
  if (std::sscanf(value, "%u:%u:%lf", &hours, &minutes, &final_seconds) == 3) {
    return static_cast<uint64_t>(
        ((hours * 60.0 + minutes) * 60.0 + final_seconds) * 1000.0 + 0.5);
  }
  if (std::sscanf(value, "%u:%lf", &minutes, &final_seconds) == 2) {
    return static_cast<uint64_t>((minutes * 60.0 + final_seconds) * 1000.0 +
                                 0.5);
  }
  return 0;
}

struct TagState {
  GsfTags tags;
  uint64_t length_ms = 0;
  uint64_t fade_ms = 0;
};

int read_tag(void *context, const char *name, const char *value) {
  if (!context || !name || !value)
    return 0;
  auto *state = static_cast<TagState *>(context);
  if (!strcasecmp(name, "title"))
    state->tags.title = value;
  else if (!strcasecmp(name, "game"))
    state->tags.game = value;
  else if (!strcasecmp(name, "artist"))
    state->tags.artist = value;
  else if (!strcasecmp(name, "year"))
    state->tags.year = value;
  else if (!strcasecmp(name, "genre"))
    state->tags.genre = value;
  else if (!strcasecmp(name, "copyright"))
    state->tags.copyright = value;
  else if (!strcasecmp(name, "gsfby"))
    state->tags.ripper = value;
  else if (!strcasecmp(name, "comment"))
    state->tags.comment = value;
  else if (!strcasecmp(name, "length"))
    state->length_ms = parse_time_ms(value);
  else if (!strcasecmp(name, "fade"))
    state->fade_ms = parse_time_ms(value);
  return 0;
}

void *source_open(void *, const char *path) { return std::fopen(path, "rb"); }

size_t source_read(void *buffer, size_t size, size_t count, void *handle) {
  return std::fread(buffer, size, count, static_cast<FILE *>(handle));
}

int source_seek(void *handle, int64_t offset, int whence) {
  return fseeko(static_cast<FILE *>(handle), static_cast<off_t>(offset), whence);
}

int source_close(void *handle) {
  return std::fclose(static_cast<FILE *>(handle));
}

long source_tell(void *handle) { return std::ftell(static_cast<FILE *>(handle)); }

psf_file_callbacks source_callbacks = {
    "/|\\", nullptr, source_open, source_read, source_seek, source_close,
    source_tell};

bool initialize_core(std::vector<uint8_t> &rom, int sample_rate) {
  // Several GSF rips intentionally exercise BIOS and I/O edge cases that mGBA
  // reports every frame. Logging those expected conditions from the audio
  // thread is both noisy and capable of causing playback underruns.
  mLogSetDefaultLogger(&gSilentLogger);
  gState.core = mCoreCreate(mPLATFORM_GBA);
  if (!gState.core)
    return false;
  mCoreInitConfig(gState.core, "vgmp-gsf");
  if (!gState.core->init(gState.core))
    return false;

  gState.core->setAudioBufferSize(gState.core, 2048);
  blip_set_rates(gState.core->getAudioChannel(gState.core, 0),
                 gState.core->frequency(gState.core), sample_rate);
  blip_set_rates(gState.core->getAudioChannel(gState.core, 1),
                 gState.core->frequency(gState.core), sample_rate);

  VFile *vf = VFileFromMemory(rom.data(), rom.size());
  if (!vf || !gState.core->loadROM(gState.core, vf)) {
    if (vf)
      vf->close(vf);
    return false;
  }
  gState.core->reset(gState.core);

  gState.initial_state.resize(gState.core->stateSize(gState.core));
  return gState.core->saveState(gState.core, gState.initial_state.data());
}

void clear_audio_buffers() {
  if (!gState.core)
    return;
  blip_clear(gState.core->getAudioChannel(gState.core, 0));
  blip_clear(gState.core->getAudioChannel(gState.core, 1));
}

int render_unbounded(int16_t *stereo, int frames) {
  if (!gState.core || !stereo || frames <= 0)
    return 0;
  auto *left = gState.core->getAudioChannel(gState.core, 0);
  auto *right = gState.core->getAudioChannel(gState.core, 1);
  int written = 0;
  while (written < frames) {
    int available = blip_samples_avail(left);
    if (available <= 0) {
      gState.core->runFrame(gState.core);
      available = blip_samples_avail(left);
      if (available <= 0)
        break;
    }
    const int count = std::min(available, frames - written);
    const int got =
        blip_read_samples(left, stereo + written * 2, count, true);
    blip_read_samples(right, stereo + written * 2 + 1, got, true);
    written += got;
    if (got <= 0)
      break;
  }
  return written;
}

} // namespace

bool gsf_open(const char *path, int sample_rate) {
  gsf_close();
  if (!path || sample_rate <= 0)
    return false;

  LoaderState loader;
  TagState tag_state;
  const int result =
      psf_load(path, &source_callbacks, GSF_PSF_VERSION, load_gsf_section,
               &loader, read_tag, &tag_state, 0, nullptr, nullptr);
  if (result != GSF_PSF_VERSION || loader.rom.empty())
    return false;

  gState.rom = std::move(loader.rom);
  gState.sample_rate = sample_rate;
  gState.tags = std::move(tag_state.tags);
  const uint64_t duration_ms = tag_state.length_ms + tag_state.fade_ms;
  gState.total_samples = duration_ms * static_cast<uint64_t>(sample_rate) / 1000;
  gState.current_sample = 0;
  if (!initialize_core(gState.rom, sample_rate)) {
    gsf_close();
    return false;
  }
  return true;
}

void gsf_close() {
  if (gState.core) {
    gState.core->unloadROM(gState.core);
    mCoreConfigDeinit(&gState.core->config);
    gState.core->deinit(gState.core);
  }
  gState = BackendState{};
}

int gsf_render(int16_t *stereo, int frames) {
  if (!gState.core || !stereo || frames <= 0)
    return 0;
  if (gState.total_samples && gState.current_sample >= gState.total_samples)
    return 0;
  const uint64_t remaining =
      gState.total_samples
          ? gState.total_samples - gState.current_sample
          : static_cast<uint64_t>(frames);
  const int requested =
      static_cast<int>(std::min<uint64_t>(frames, remaining));
  const int written = render_unbounded(stereo, requested);
  gState.current_sample += written;
  return written;
}

bool gsf_seek(uint64_t sample) {
  if (!gState.core || gState.initial_state.empty())
    return false;
  if (gState.total_samples)
    sample = std::min(sample, gState.total_samples);
  if (!gState.core->loadState(gState.core, gState.initial_state.data()))
    return false;
  clear_audio_buffers();
  gState.current_sample = 0;
  std::vector<int16_t> scratch(4096 * 2);
  while (gState.current_sample < sample) {
    const int count = static_cast<int>(
        std::min<uint64_t>(4096, sample - gState.current_sample));
    const int written = render_unbounded(scratch.data(), count);
    if (written <= 0)
      return false;
    gState.current_sample += written;
  }
  return true;
}

bool gsf_is_ended() {
  return gState.total_samples &&
         gState.current_sample >= gState.total_samples;
}

uint64_t gsf_current_sample() { return gState.current_sample; }

uint64_t gsf_total_samples() { return gState.total_samples; }

const GsfTags &gsf_tags() { return gState.tags; }
