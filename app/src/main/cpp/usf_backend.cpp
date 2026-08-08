#include "usf_backend.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <vector>

#include "psflib.h"
#include "usf/usf.h"

namespace {

constexpr uint8_t USF_VERSION = 0x21;

struct BackendState {
  std::vector<uint8_t> emulator;
  uint64_t current_sample = 0;
  uint64_t total_samples = 0;
  int sample_rate = 44100;
  UsfTags tags;
};

BackendState gState;

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
  double seconds_part = 0.0;
  if (std::sscanf(value, "%u:%u:%lf", &hours, &minutes, &seconds_part) == 3)
    return static_cast<uint64_t>(((hours * 60.0 + minutes) * 60.0 + seconds_part) * 1000.0 + 0.5);
  if (std::sscanf(value, "%u:%lf", &minutes, &seconds_part) == 2)
    return static_cast<uint64_t>((minutes * 60.0 + seconds_part) * 1000.0 + 0.5);
  return 0;
}

void *source_open(void *, const char *path) { return std::fopen(path, "rb"); }
size_t source_read(void *buffer, size_t size, size_t count, void *handle) {
  return std::fread(buffer, size, count, static_cast<FILE *>(handle));
}
int source_seek(void *handle, int64_t offset, int whence) {
  return fseeko(static_cast<FILE *>(handle), static_cast<off_t>(offset), whence);
}
int source_close(void *handle) { return std::fclose(static_cast<FILE *>(handle)); }
long source_tell(void *handle) { return std::ftell(static_cast<FILE *>(handle)); }

psf_file_callbacks source_callbacks = {
    "/|\\", nullptr, source_open, source_read, source_seek, source_close,
    source_tell};

int load_section(void *context, const uint8_t *, size_t exe_size,
                 const uint8_t *reserved, size_t reserved_size) {
  if (exe_size != 0 || !context)
    return -1;
  return usf_upload_section(context, reserved, reserved_size);
}

struct TagState {
  UsfTags tags;
  uint64_t length_ms = 0;
  bool enable_compare = false;
  bool enable_fifo_full = false;
};

int read_tag(void *context, const char *name, const char *value) {
  auto *state = static_cast<TagState *>(context);
  if (!strcasecmp(name, "title")) state->tags.title = value;
  else if (!strcasecmp(name, "game")) state->tags.game = value;
  else if (!strcasecmp(name, "artist")) state->tags.artist = value;
  else if (!strcasecmp(name, "year")) state->tags.year = value;
  else if (!strcasecmp(name, "usfby")) state->tags.ripper = value;
  else if (!strcasecmp(name, "comment")) state->tags.comment = value;
  else if (!strcasecmp(name, "length")) state->length_ms = parse_time_ms(value);
  else if (!strcasecmp(name, "_enablecompare") && *value) state->enable_compare = true;
  else if (!strcasecmp(name, "_enablefifofull") && *value) state->enable_fifo_full = true;
  return 0;
}

} // namespace

bool usf_backend_open(const char *path, int sample_rate) {
  usf_backend_close();
  if (!path || sample_rate <= 0)
    return false;
  gState.emulator.resize(usf_get_state_size());
  usf_clear(gState.emulator.data());
  TagState tags;
  const int result = psf_load(path, &source_callbacks, USF_VERSION, load_section,
                              gState.emulator.data(), read_tag, &tags, 1,
                              nullptr, nullptr);
  if (result != USF_VERSION) {
    usf_backend_close();
    return false;
  }
  usf_set_compare(gState.emulator.data(), tags.enable_compare ? 1 : 0);
  usf_set_fifo_full(gState.emulator.data(), tags.enable_fifo_full ? 1 : 0);
  usf_set_hle_audio(gState.emulator.data(), 1);
  gState.sample_rate = sample_rate;
  gState.total_samples = tags.length_ms * static_cast<uint64_t>(sample_rate) / 1000;
  gState.tags = std::move(tags.tags);
  return true;
}

void usf_backend_close() {
  if (!gState.emulator.empty())
    usf_shutdown(gState.emulator.data());
  gState = BackendState{};
}

int usf_backend_render(int16_t *stereo, int frames) {
  if (gState.emulator.empty() || !stereo || frames <= 0)
    return 0;
  if (usf_render_resampled(gState.emulator.data(), stereo, frames, gState.sample_rate))
    return 0;
  gState.current_sample += frames;
  return frames;
}

bool usf_backend_seek(uint64_t sample) {
  if (gState.emulator.empty())
    return false;
  usf_restart(gState.emulator.data());
  gState.current_sample = 0;
  while (gState.current_sample < sample) {
    const size_t count = static_cast<size_t>(std::min<uint64_t>(4096, sample - gState.current_sample));
    if (usf_render_resampled(gState.emulator.data(), nullptr, count, gState.sample_rate))
      return false;
    gState.current_sample += count;
  }
  return true;
}

uint64_t usf_backend_current_sample() { return gState.current_sample; }
uint64_t usf_backend_total_samples() { return gState.total_samples; }
const UsfTags &usf_backend_tags() { return gState.tags; }
