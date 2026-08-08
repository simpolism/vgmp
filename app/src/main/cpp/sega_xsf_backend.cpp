#include "sega_xsf_backend.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <vector>

#include "psflib.h"
#include "sega.h"

namespace {

constexpr uint8_t SSF_VERSION = 0x11;
constexpr uint8_t DSF_VERSION = 0x12;

struct BackendState {
  std::vector<uint8_t> program;
  std::vector<uint8_t> emulator;
  uint64_t current_sample = 0;
  uint64_t total_samples = 0;
  uint8_t version = 0;
  SegaXsfTags tags;
};

BackendState gState;
bool gSegaInitialized = false;

uint32_t read_le32(const uint8_t *p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

void write_le32(uint8_t *p, uint32_t value) {
  p[0] = static_cast<uint8_t>(value);
  p[1] = static_cast<uint8_t>(value >> 8);
  p[2] = static_cast<uint8_t>(value >> 16);
  p[3] = static_cast<uint8_t>(value >> 24);
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

int load_section(void *context, const uint8_t *exe, size_t exe_size,
                 const uint8_t *, size_t) {
  if (!context || !exe || exe_size < 4)
    return -1;
  auto *program = static_cast<std::vector<uint8_t> *>(context);
  if (program->empty()) {
    program->assign(exe, exe + exe_size);
    return 0;
  }

  uint32_t destination_start = read_le32(program->data()) & 0x7FFFFF;
  const uint32_t source_start = read_le32(exe) & 0x7FFFFF;
  size_t destination_length = std::min<size_t>(program->size() - 4, 0x800000);
  const size_t source_length = std::min<size_t>(exe_size - 4, 0x800000);
  if (source_start < destination_start) {
    const size_t prefix = destination_start - source_start;
    program->resize(destination_length + 4 + prefix);
    std::memmove(program->data() + 4 + prefix, program->data() + 4,
                 destination_length);
    std::memset(program->data() + 4, 0, prefix);
    destination_length += prefix;
    destination_start = source_start;
    write_le32(program->data(), destination_start);
  }
  const uint64_t source_end = static_cast<uint64_t>(source_start) + source_length;
  const uint64_t destination_end = static_cast<uint64_t>(destination_start) + destination_length;
  if (source_end > destination_end) {
    const size_t suffix = static_cast<size_t>(source_end - destination_end);
    program->resize(destination_length + 4 + suffix, 0);
  }
  std::memcpy(program->data() + 4 + (source_start - destination_start), exe + 4,
              source_length);
  return 0;
}

struct TagState {
  SegaXsfTags tags;
  uint64_t length_ms = 0;
};

int read_tag(void *context, const char *name, const char *value) {
  auto *state = static_cast<TagState *>(context);
  if (!strcasecmp(name, "title")) state->tags.title = value;
  else if (!strcasecmp(name, "game")) state->tags.game = value;
  else if (!strcasecmp(name, "artist")) state->tags.artist = value;
  else if (!strcasecmp(name, "year")) state->tags.year = value;
  else if (!strcasecmp(name, "ssfby") || !strcasecmp(name, "dsfby")) state->tags.ripper = value;
  else if (!strcasecmp(name, "comment")) state->tags.comment = value;
  else if (!strcasecmp(name, "length")) state->length_ms = parse_time_ms(value);
  return 0;
}

bool initialize_emulator() {
  if (!gSegaInitialized) {
    if (sega_init())
      return false;
    gSegaInitialized = true;
  }
  const uint8_t core_version = gState.version - 0x10;
  gState.emulator.resize(sega_get_state_size(core_version));
  sega_clear_state(gState.emulator.data(), core_version);
  sega_enable_dry(gState.emulator.data(), 1);
  sega_enable_dsp(gState.emulator.data(), 1);
  // The portable DSP is fast enough on modern Android and avoids executable
  // memory requirements imposed by the optional DSP recompiler.
  sega_enable_dsp_dynarec(gState.emulator.data(), 0);
  return sega_upload_program(gState.emulator.data(), gState.program.data(),
                             gState.program.size()) == 0;
}

} // namespace

bool sega_xsf_open(const char *path, int sample_rate) {
  sega_xsf_close();
  if (!path || sample_rate != 44100)
    return false;
  const int detected = psf_load(path, &source_callbacks, 0, nullptr, nullptr,
                                nullptr, nullptr, 0, nullptr, nullptr);
  if (detected != SSF_VERSION && detected != DSF_VERSION)
    return false;
  gState.version = static_cast<uint8_t>(detected);
  TagState tags;
  if (psf_load(path, &source_callbacks, gState.version, nullptr, nullptr,
               read_tag, &tags, 0, nullptr, nullptr) <= 0 ||
      psf_load(path, &source_callbacks, gState.version, load_section,
               &gState.program, nullptr, nullptr, 0, nullptr, nullptr) < 0 ||
      gState.program.size() < 4) {
    sega_xsf_close();
    return false;
  }
  const uint32_t start = read_le32(gState.program.data()) & 0x7FFFFF;
  const size_t max_length = gState.version == DSF_VERSION ? 0x800000 : 0x80000;
  if (start >= max_length) {
    sega_xsf_close();
    return false;
  }
  if (gState.program.size() - 4 > max_length - start)
    gState.program.resize(max_length - start + 4);
  gState.tags = std::move(tags.tags);
  gState.total_samples = tags.length_ms * 44100 / 1000;
  if (!initialize_emulator()) {
    sega_xsf_close();
    return false;
  }
  return true;
}

void sega_xsf_close() { gState = BackendState{}; }

int sega_xsf_render(int16_t *stereo, int frames) {
  if (gState.emulator.empty() || !stereo || frames <= 0)
    return 0;
  int written = 0;
  int empty_iterations = 0;
  while (written < frames && empty_iterations < 32) {
    uint32_t requested = static_cast<uint32_t>(frames - written);
    const int result = sega_execute(gState.emulator.data(), 0x7FFFFFFF,
                                    stereo + written * 2, &requested);
    if (result < 0)
      break;
    if (requested == 0) {
      ++empty_iterations;
      continue;
    }
    empty_iterations = 0;
    written += requested;
  }
  gState.current_sample += written;
  return written;
}

bool sega_xsf_seek(uint64_t sample) {
  if (gState.program.empty() || !initialize_emulator())
    return false;
  gState.current_sample = 0;
  while (gState.current_sample < sample) {
    uint32_t requested = static_cast<uint32_t>(std::min<uint64_t>(4096, sample - gState.current_sample));
    const int result = sega_execute(gState.emulator.data(), 0x7FFFFFFF, nullptr,
                                    &requested);
    if (result < 0 || requested == 0)
      return false;
    gState.current_sample += requested;
  }
  return true;
}

uint64_t sega_xsf_current_sample() { return gState.current_sample; }
uint64_t sega_xsf_total_samples() { return gState.total_samples; }
bool sega_xsf_is_dreamcast() { return gState.version == DSF_VERSION; }
const SegaXsfTags &sega_xsf_tags() { return gState.tags; }
