#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "base.h"
#include "field.h"
#include "gbuffer.h"
#include "sim.h"
#include "vmio.h"

typedef struct {
  Field field;
  Mbuf_reusable mbuf;
  Oevent_list events;
  Usz tick;
  Usz seed;
} Android_orca_session;

static bool is_newline(char c) { return c == '\n' || c == '\r'; }

static void measure_grid(char const *source, Usz *out_height, Usz *out_width) {
  Usz height = 0;
  Usz width = 0;
  Usz current_width = 0;
  bool saw_any = false;

  for (char const *p = source; *p != '\0'; ++p) {
    if (is_newline(*p)) {
      if (*p == '\r' && p[1] == '\n')
        ++p;
      if (current_width > width)
        width = current_width;
      current_width = 0;
      ++height;
      saw_any = true;
    } else {
      ++current_width;
      saw_any = true;
    }
  }

  if (current_width > 0 || !saw_any) {
    if (current_width > width)
      width = current_width;
    ++height;
  }

  if (width == 0)
    width = 1;
  if (height == 0)
    height = 1;

  *out_height = height;
  *out_width = width;
}

static Glyph normalize_glyph(char c) {
  if (c == ' ')
    return '.';
  return orca_is_valid_glyph(c) ? c : '.';
}

static void load_grid(char const *source, Field *field) {
  Usz y = 0;
  Usz x = 0;
  for (char const *p = source; *p != '\0' && y < field->height; ++p) {
    if (is_newline(*p)) {
      if (*p == '\r' && p[1] == '\n')
        ++p;
      ++y;
      x = 0;
    } else if (x < field->width) {
      field->buffer[y * field->width + x] = normalize_glyph(*p);
      ++x;
    }
  }
}

static char *field_to_string(Field const *field) {
  Usz line = (Usz)field->width + 1;
  Usz total = line * (Usz)field->height;
  char *out = (char *)malloc(total + 1);
  if (out == NULL)
    return NULL;

  char *cursor = out;
  for (Usz y = 0; y < field->height; ++y) {
    memcpy(cursor, field->buffer + y * field->width, field->width);
    cursor += field->width;
    *cursor++ = '\n';
  }
  *cursor = '\0';
  return out;
}

static void session_load_source(Android_orca_session *session,
                                char const *source, Usz seed) {
  Usz height = 0;
  Usz width = 0;
  measure_grid(source, &height, &width);
  field_resize_raw(&session->field, height, width);
  memset(session->field.buffer, '.', height * width);
  load_grid(source, &session->field);
  mbuf_reusable_ensure_size(&session->mbuf, height, width);
  oevent_list_clear(&session->events);
  session->tick = 0;
  session->seed = seed;
}

static void session_step(Android_orca_session *session, Usz ticks) {
  for (Usz i = 0; i < ticks; ++i) {
    mbuffer_clear(session->mbuf.buffer, session->field.height,
                  session->field.width);
    oevent_list_clear(&session->events);
    orca_run(session->field.buffer, session->mbuf.buffer, session->field.height,
             session->field.width, session->tick, &session->events,
             session->seed);
    ++session->tick;
  }
}

static void append_str(char **cursor, Usz *remaining, char const *src) {
  while (*src != '\0' && *remaining > 1) {
    **cursor = *src;
    ++*cursor;
    ++src;
    --*remaining;
  }
}

static void append_uint(char **cursor, Usz *remaining, Usz value) {
  char tmp[32];
  int used = snprintf(tmp, sizeof tmp, "%zu", value);
  if (used > 0)
    append_str(cursor, remaining, tmp);
}

static char *events_to_string(Oevent_list const *events) {
  if (events->count == 0) {
    char *empty = (char *)malloc(10);
    if (empty != NULL)
      memcpy(empty, "No events", 10);
    return empty;
  }

  Usz capacity = events->count * 96 + 1;
  char *out = (char *)malloc(capacity);
  if (out == NULL)
    return NULL;
  char *cursor = out;
  Usz remaining = capacity;

  for (Usz i = 0; i < events->count; ++i) {
    Oevent const *event = events->buffer + i;
    append_uint(&cursor, &remaining, i + 1);
    append_str(&cursor, &remaining, ": ");
    switch (event->any.oevent_type) {
    case Oevent_type_midi_note:
      append_str(&cursor, &remaining, event->midi_note.mono ? "MIDI mono ch "
                                                            : "MIDI note ch ");
      append_uint(&cursor, &remaining, event->midi_note.channel);
      append_str(&cursor, &remaining, " oct ");
      append_uint(&cursor, &remaining, event->midi_note.octave);
      append_str(&cursor, &remaining, " note ");
      append_uint(&cursor, &remaining, event->midi_note.note);
      append_str(&cursor, &remaining, " vel ");
      append_uint(&cursor, &remaining, event->midi_note.velocity);
      append_str(&cursor, &remaining, " len ");
      append_uint(&cursor, &remaining, event->midi_note.duration);
      break;
    case Oevent_type_midi_cc:
      append_str(&cursor, &remaining, "MIDI cc ch ");
      append_uint(&cursor, &remaining, event->midi_cc.channel);
      append_str(&cursor, &remaining, " ctl ");
      append_uint(&cursor, &remaining, event->midi_cc.control);
      append_str(&cursor, &remaining, " val ");
      append_uint(&cursor, &remaining, event->midi_cc.value);
      break;
    case Oevent_type_midi_pb:
      append_str(&cursor, &remaining, "MIDI pb ch ");
      append_uint(&cursor, &remaining, event->midi_pb.channel);
      append_str(&cursor, &remaining, " lsb ");
      append_uint(&cursor, &remaining, event->midi_pb.lsb);
      append_str(&cursor, &remaining, " msb ");
      append_uint(&cursor, &remaining, event->midi_pb.msb);
      break;
    case Oevent_type_osc_ints:
      append_str(&cursor, &remaining, "OSC ");
      if (remaining > 1) {
        *cursor++ = event->osc_ints.glyph;
        --remaining;
      }
      for (U8 n = 0; n < event->osc_ints.count; ++n) {
        append_str(&cursor, &remaining, " ");
        append_uint(&cursor, &remaining, event->osc_ints.numbers[n]);
      }
      break;
    case Oevent_type_udp_string:
      append_str(&cursor, &remaining, "UDP ");
      for (U8 n = 0; n < event->udp_string.count && remaining > 1; ++n) {
        *cursor++ = event->udp_string.chars[n];
        --remaining;
      }
      break;
    default:
      append_str(&cursor, &remaining, "Unknown event");
      break;
    }
    append_str(&cursor, &remaining, "\n");
  }
  *cursor = '\0';
  return out;
}

static char *events_to_wire_string(Oevent_list const *events) {
  Usz capacity = events->count * 160 + 1;
  char *out = (char *)malloc(capacity);
  if (out == NULL)
    return NULL;
  char *cursor = out;
  Usz remaining = capacity;

  for (Usz i = 0; i < events->count; ++i) {
    Oevent const *event = events->buffer + i;
    switch (event->any.oevent_type) {
    case Oevent_type_midi_note:
      append_str(&cursor, &remaining, "MN ");
      append_uint(&cursor, &remaining, event->midi_note.channel);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_note.octave);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_note.note);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_note.velocity);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_note.duration);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_note.mono);
      append_str(&cursor, &remaining, "\n");
      break;
    case Oevent_type_midi_cc:
      append_str(&cursor, &remaining, "MC ");
      append_uint(&cursor, &remaining, event->midi_cc.channel);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_cc.control);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_cc.value);
      append_str(&cursor, &remaining, "\n");
      break;
    case Oevent_type_midi_pb:
      append_str(&cursor, &remaining, "MP ");
      append_uint(&cursor, &remaining, event->midi_pb.channel);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_pb.lsb);
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->midi_pb.msb);
      append_str(&cursor, &remaining, "\n");
      break;
    case Oevent_type_osc_ints:
      append_str(&cursor, &remaining, "OI ");
      if (remaining > 1) {
        *cursor++ = event->osc_ints.glyph;
        --remaining;
      }
      append_str(&cursor, &remaining, " ");
      append_uint(&cursor, &remaining, event->osc_ints.count);
      for (U8 n = 0; n < event->osc_ints.count; ++n) {
        append_str(&cursor, &remaining, " ");
        append_uint(&cursor, &remaining, event->osc_ints.numbers[n]);
      }
      append_str(&cursor, &remaining, "\n");
      break;
    case Oevent_type_udp_string:
      append_str(&cursor, &remaining, "US ");
      append_uint(&cursor, &remaining, event->udp_string.count);
      for (U8 n = 0; n < event->udp_string.count; ++n) {
        append_str(&cursor, &remaining, " ");
        append_uint(&cursor, &remaining, (U8)event->udp_string.chars[n]);
      }
      append_str(&cursor, &remaining, "\n");
      break;
    default:
      break;
    }
  }
  *cursor = '\0';
  return out;
}

JNIEXPORT jstring JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_run(JNIEnv *env, jclass clazz,
                                             jstring source, jint ticks,
                                             jint seed) {
  (void)clazz;
  char const *source_chars = (*env)->GetStringUTFChars(env, source, NULL);
  if (source_chars == NULL)
    return NULL;

  Usz height = 0;
  Usz width = 0;
  measure_grid(source_chars, &height, &width);

  Field field;
  field_init_fill(&field, height, width, '.');
  load_grid(source_chars, &field);
  (*env)->ReleaseStringUTFChars(env, source, source_chars);

  Mbuf_reusable mbuf;
  mbuf_reusable_init(&mbuf);
  mbuf_reusable_ensure_size(&mbuf, field.height, field.width);

  Oevent_list events;
  oevent_list_init(&events);

  Usz max_ticks = ticks < 0 ? 0 : (Usz)ticks;
  for (Usz i = 0; i < max_ticks; ++i) {
    mbuffer_clear(mbuf.buffer, field.height, field.width);
    oevent_list_clear(&events);
    orca_run(field.buffer, mbuf.buffer, field.height, field.width, i, &events,
             (Usz)seed);
  }

  char *output = field_to_string(&field);
  jstring result = output == NULL ? (*env)->NewStringUTF(env, "Out of memory")
                                  : (*env)->NewStringUTF(env, output);

  free(output);
  oevent_list_deinit(&events);
  mbuf_reusable_deinit(&mbuf);
  field_deinit(&field);
  return result;
}

JNIEXPORT jlong JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_create(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  Android_orca_session *session =
      (Android_orca_session *)malloc(sizeof(Android_orca_session));
  if (session == NULL)
    return 0;
  field_init(&session->field);
  mbuf_reusable_init(&session->mbuf);
  oevent_list_init(&session->events);
  session->tick = 0;
  session->seed = 0;
  return (jlong)(intptr_t)session;
}

JNIEXPORT void JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_destroy(JNIEnv *env, jclass clazz,
                                                 jlong handle) {
  (void)env;
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return;
  oevent_list_deinit(&session->events);
  mbuf_reusable_deinit(&session->mbuf);
  field_deinit(&session->field);
  free(session);
}

JNIEXPORT void JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_load(JNIEnv *env, jclass clazz,
                                              jlong handle, jstring source,
                                              jint seed) {
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return;
  char const *source_chars = (*env)->GetStringUTFChars(env, source, NULL);
  if (source_chars == NULL)
    return;
  session_load_source(session, source_chars, seed < 0 ? 0 : (Usz)seed);
  (*env)->ReleaseStringUTFChars(env, source, source_chars);
}

JNIEXPORT jstring JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_step(JNIEnv *env, jclass clazz,
                                              jlong handle, jint ticks) {
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return (*env)->NewStringUTF(env, "No session");
  session_step(session, ticks < 0 ? 0 : (Usz)ticks);
  char *output = field_to_string(&session->field);
  jstring result = output == NULL ? (*env)->NewStringUTF(env, "Out of memory")
                                  : (*env)->NewStringUTF(env, output);
  free(output);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_getGrid(JNIEnv *env, jclass clazz,
                                                 jlong handle) {
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return (*env)->NewStringUTF(env, "No session");
  char *output = field_to_string(&session->field);
  jstring result = output == NULL ? (*env)->NewStringUTF(env, "Out of memory")
                                  : (*env)->NewStringUTF(env, output);
  free(output);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_getEvents(JNIEnv *env, jclass clazz,
                                                   jlong handle) {
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return (*env)->NewStringUTF(env, "No session");
  char *output = events_to_string(&session->events);
  jstring result = output == NULL ? (*env)->NewStringUTF(env, "Out of memory")
                                  : (*env)->NewStringUTF(env, output);
  free(output);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_getEventsWire(JNIEnv *env,
                                                       jclass clazz,
                                                       jlong handle) {
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return (*env)->NewStringUTF(env, "");
  char *output = events_to_wire_string(&session->events);
  jstring result = output == NULL ? (*env)->NewStringUTF(env, "")
                                  : (*env)->NewStringUTF(env, output);
  free(output);
  return result;
}

JNIEXPORT jlong JNICALL
Java_com_hundredrabbits_orcac_OrcaNative_getTick(JNIEnv *env, jclass clazz,
                                                 jlong handle) {
  (void)env;
  (void)clazz;
  Android_orca_session *session = (Android_orca_session *)(intptr_t)handle;
  if (session == NULL)
    return 0;
  return (jlong)session->tick;
}
