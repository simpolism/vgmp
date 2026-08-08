#include "twosf_backend.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <vector>

#include "psflib.h"
#include "state.h"
#include <zlib.h>

namespace {

constexpr uint8_t TWO_SF_VERSION = 0x24;

struct LoaderState {
  uint8_t *rom = nullptr;
  uint8_t *save = nullptr;
  size_t rom_size = 0;
  size_t save_size = 0;
  int initial_frames = -1;
  int sync_type = 0;
  int clockdown = 0;
  int arm9_clockdown = 0;
  int arm7_clockdown = 0;
};

struct BackendState {
  NDS_state *emu = nullptr;
  LoaderState loader;
  uint64_t current_sample = 0;
  uint64_t total_samples = 0;
  TwoSfTags tags;
};

BackendState gState;

uint32_t read_le32(const uint8_t *p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
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

bool merge_map(uint8_t *&target, size_t &target_size, const uint8_t *data,
               size_t data_size, bool round_to_power_of_two) {
  if (!data || data_size < 8)
    return false;
  const size_t offset = read_le32(data);
  const size_t payload_size = read_le32(data + 4);
  if (payload_size > data_size - 8 || offset > SIZE_MAX - payload_size)
    return false;
  const size_t required = offset + payload_size;
  size_t allocation = required;
  if (round_to_power_of_two && allocation > 0) {
    --allocation;
    allocation |= allocation >> 1;
    allocation |= allocation >> 2;
    allocation |= allocation >> 4;
    allocation |= allocation >> 8;
    allocation |= allocation >> 16;
#if SIZE_MAX > UINT32_MAX
    allocation |= allocation >> 32;
#endif
    ++allocation;
  }
  if (required > target_size) {
    auto *resized = static_cast<uint8_t *>(std::realloc(target, allocation + 10));
    if (!resized)
      return false;
    std::memset(resized + target_size, 0, allocation + 10 - target_size);
    target = resized;
    target_size = allocation;
  }
  std::memcpy(target + offset, data + 8, payload_size);
  return true;
}

bool merge_compressed_save(LoaderState *state, const uint8_t *data,
                           size_t data_size) {
  uLongf output_size = 8;
  std::vector<uint8_t> output(output_size);
  int result = Z_BUF_ERROR;
  while (result == Z_BUF_ERROR) {
    result = uncompress(output.data(), &output_size, data, data_size);
    if (result == Z_OK)
      break;
    if (result != Z_BUF_ERROR)
      return false;
    if (output_size >= 8)
      output_size = std::max<uLongf>(output_size * 2, read_le32(output.data() + 4) + 8);
    else
      output_size *= 2;
    output.resize(output_size);
  }
  output.resize(output_size);
  return merge_map(state->save, state->save_size, output.data(), output.size(), false);
}

int load_section(void *context, const uint8_t *exe, size_t exe_size,
                 const uint8_t *reserved, size_t reserved_size) {
  auto *state = static_cast<LoaderState *>(context);
  if (exe_size && !merge_map(state->rom, state->rom_size, exe, exe_size, true))
    return -1;
  size_t position = 0;
  while (position + 12 <= reserved_size) {
    const uint32_t type = read_le32(reserved + position);
    const size_t size = read_le32(reserved + position + 4);
    if (size > reserved_size - position - 12)
      return -1;
    if (type == 0x45564153 &&
        !merge_compressed_save(state, reserved + position + 12, size))
      return -1;
    position += 12 + size;
  }
  return 0;
}

int read_loader_tag(void *context, const char *name, const char *value) {
  auto *state = static_cast<LoaderState *>(context);
  if (!strcasecmp(name, "_frames"))
    state->initial_frames = std::strtol(value, nullptr, 10);
  else if (!strcasecmp(name, "_clockdown"))
    state->clockdown = std::strtol(value, nullptr, 10);
  else if (!strcasecmp(name, "_vio2sf_sync_type"))
    state->sync_type = std::strtol(value, nullptr, 10);
  else if (!strcasecmp(name, "_vio2sf_arm9_clockdown_level"))
    state->arm9_clockdown = std::strtol(value, nullptr, 10);
  else if (!strcasecmp(name, "_vio2sf_arm7_clockdown_level"))
    state->arm7_clockdown = std::strtol(value, nullptr, 10);
  return 0;
}

struct TagState {
  TwoSfTags tags;
  uint64_t length_ms = 0;
};

int read_metadata_tag(void *context, const char *name, const char *value) {
  auto *state = static_cast<TagState *>(context);
  if (!strcasecmp(name, "title")) state->tags.title = value;
  else if (!strcasecmp(name, "game")) state->tags.game = value;
  else if (!strcasecmp(name, "artist")) state->tags.artist = value;
  else if (!strcasecmp(name, "year")) state->tags.year = value;
  else if (!strcasecmp(name, "2sfby")) state->tags.ripper = value;
  else if (!strcasecmp(name, "comment")) state->tags.comment = value;
  else if (!strcasecmp(name, "length")) state->length_ms = parse_time_ms(value);
  return 0;
}

bool initialize_emulator() {
  gState.emu = new NDS_state();
  if (state_init(gState.emu))
    return false;
  LoaderState &loader = gState.loader;
  gState.emu->dwInterpolation = 4;
  gState.emu->dwChannelMute = 0;
  gState.emu->initial_frames = loader.initial_frames;
  gState.emu->sync_type = loader.sync_type;
  gState.emu->arm7_clockdown_level = loader.arm7_clockdown ? loader.arm7_clockdown : loader.clockdown;
  gState.emu->arm9_clockdown_level = loader.arm9_clockdown ? loader.arm9_clockdown : loader.clockdown;
  if (loader.rom)
    state_setrom(gState.emu, loader.rom, loader.rom_size, 0);
  state_loadstate(gState.emu, loader.save, loader.save_size);
  return true;
}

} // namespace

bool twosf_open(const char *path, int sample_rate) {
  twosf_close();
  if (!path || sample_rate != 44100)
    return false;
  TagState tag_state;
  if (psf_load(path, &source_callbacks, TWO_SF_VERSION, nullptr, nullptr,
               read_metadata_tag, &tag_state, 0, nullptr, nullptr) <= 0)
    return false;
  if (psf_load(path, &source_callbacks, TWO_SF_VERSION, load_section,
               &gState.loader, read_loader_tag, &gState.loader, 1, nullptr,
               nullptr) < 0 || (!gState.loader.rom && !gState.loader.save)) {
    twosf_close();
    return false;
  }
  gState.tags = std::move(tag_state.tags);
  gState.total_samples = tag_state.length_ms * 44100 / 1000;
  if (!initialize_emulator()) {
    twosf_close();
    return false;
  }
  return true;
}

void twosf_close() {
  if (gState.emu) {
    state_deinit(gState.emu);
    delete gState.emu;
  }
  std::free(gState.loader.rom);
  std::free(gState.loader.save);
  gState = BackendState{};
}

int twosf_render(int16_t *stereo, int frames) {
  if (!gState.emu || !stereo || frames <= 0)
    return 0;
  state_render(gState.emu, stereo, static_cast<unsigned>(frames));
  gState.current_sample += frames;
  return frames;
}

bool twosf_seek(uint64_t sample) {
  if (!gState.emu)
    return false;
  state_deinit(gState.emu);
  delete gState.emu;
  gState.emu = nullptr;
  if (!initialize_emulator())
    return false;
  gState.current_sample = 0;
  std::vector<int16_t> scratch(4096 * 2);
  while (gState.current_sample < sample) {
    const int count = static_cast<int>(std::min<uint64_t>(4096, sample - gState.current_sample));
    state_render(gState.emu, scratch.data(), count);
    gState.current_sample += count;
  }
  return true;
}

uint64_t twosf_current_sample() { return gState.current_sample; }
uint64_t twosf_total_samples() { return gState.total_samples; }
const TwoSfTags &twosf_tags() { return gState.tags; }
