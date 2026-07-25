#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

struct GsfTags {
  std::string title;
  std::string game;
  std::string artist;
  std::string year;
  std::string genre;
  std::string copyright;
  std::string ripper;
  std::string comment;
};

bool gsf_open(const char *path, int sample_rate);
void gsf_close();
int gsf_render(int16_t *stereo, int frames);
bool gsf_seek(uint64_t sample);
bool gsf_is_ended();
uint64_t gsf_current_sample();
uint64_t gsf_total_samples();
const GsfTags &gsf_tags();
