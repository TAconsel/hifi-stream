/* HiFi Stream Receiver: GTK4 front end. */
#include <gtk/gtk.h>
#include <glib-unix.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "audio.h"
#include "net.h"
#include "protocol.h"

static struct {
    int      port;
    double   buffer_ms;
    int      quantum;
    gboolean headless;
    gboolean force_rate;
    gboolean remote_rate;
    gboolean local_rate;
    char    *dump;
    char    *target;
    int      log_secs;
} opt = { .port = HFS_DEFAULT_PORT, .buffer_ms = 20.0, .quantum = 256, .remote_rate = TRUE };

static const GOptionEntry entries[] = {
    { "port",       'p', 0, G_OPTION_ARG_INT,    &opt.port,       "UDP port to listen on (default 47100)", "PORT" },
    { "buffer",     'b', 0, G_OPTION_ARG_DOUBLE, &opt.buffer_ms,  "Jitter buffer safety margin in ms (default 20)", "MS" },
    { "quantum",    'q', 0, G_OPTION_ARG_INT,    &opt.quantum,    "Requested PipeWire quantum in frames (default 256)", "FRAMES" },
    { "target",     't', 0, G_OPTION_ARG_STRING, &opt.target,     "PipeWire sink to play to (node name or id)", "NODE" },
    { "force-rate", 0,   0, G_OPTION_ARG_NONE,   &opt.force_rate, "Force the PipeWire graph rate to follow the stream", NULL },
    { "remote-rate", 0,  0, G_OPTION_ARG_NONE,   &opt.remote_rate, "Rate matching on the phone: never resample here, send the correction in HFS_RX (default)", NULL },
    { "local-rate",  0,  0, G_OPTION_ARG_NONE,   &opt.local_rate,  "Rate matching here with PipeWire's resampler instead", NULL },
    { "dump",       'd', 0, G_OPTION_ARG_STRING, &opt.dump,       "Also write the received audio to a float WAV file", "FILE" },
    { "headless",   0,   0, G_OPTION_ARG_NONE,   &opt.headless,   "No window; print statistics to stdout", NULL },
    { "log",        'l', 0, G_OPTION_ARG_INT,    &opt.log_secs,   "Also print a statistics line to stderr every N seconds", "N" },
    { NULL }
};

/* ---- UI state ------------------------------------------------------------- */

struct ui {
    GtkWidget *window;
    GtkWidget *status;
    GtkWidget *sender;
    GtkWidget *format;
    GtkWidget *packets;
    GtkWidget *bitrate;
    GtkWidget *buffer;
    GtkWidget *corrections;
    GtkWidget *output;
    GtkWidget *latency;
    GtkWidget *fill_bar;
    GtkWidget *scale;
    GtkWidget *meter;
    GtkWidget *port_entry;
    GtkWidget *listen_switch;
    GtkWidget *dump_button;
    float      meter_db[2];
    float      meter_hold[2];
    int        hold_age[2];
};

static struct ui UI;

static const char *state_name(int s)
{
    switch (s) {
    case AUDIO_IDLE:       return "idle";
    case AUDIO_CONNECTING: return "connecting to PipeWire";
    case AUDIO_PREBUFFER:  return "buffering";
    case AUDIO_PLAYING:    return "playing";
    case AUDIO_ERROR:      return "PipeWire error";
    default:               return "?";
    }
}

static float to_db(float lin)
{
    if (lin <= 1e-5f) return -100.f;
    return 20.f * log10f(lin);
}

static void meter_draw(GtkDrawingArea *area, cairo_t *cr, int w, int h, gpointer data)
{
    (void)area; (void)data;
    const float min_db = -60.f;
    int bar_h = (h - 6) / 2;
    for (int c = 0; c < 2; c++) {
        int y = c * (bar_h + 6);
        cairo_set_source_rgba(cr, 0.5, 0.5, 0.5, 0.25);
        cairo_rectangle(cr, 0, y, w, bar_h);
        cairo_fill(cr);
        float db = UI.meter_db[c];
        double frac = (db - min_db) / -min_db;
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        /* green up to -12 dB, yellow up to -3, red above */
        double x_yellow = w * (1.0 - 12.0 / -min_db);
        double x_red = w * (1.0 - 3.0 / -min_db);
        double x = w * frac;
        cairo_set_source_rgb(cr, 0.20, 0.72, 0.36);
        cairo_rectangle(cr, 0, y, MIN(x, x_yellow), bar_h);
        cairo_fill(cr);
        if (x > x_yellow) {
            cairo_set_source_rgb(cr, 0.93, 0.76, 0.20);
            cairo_rectangle(cr, x_yellow, y, MIN(x, x_red) - x_yellow, bar_h);
            cairo_fill(cr);
        }
        if (x > x_red) {
            cairo_set_source_rgb(cr, 0.88, 0.25, 0.25);
            cairo_rectangle(cr, x_red, y, x - x_red, bar_h);
            cairo_fill(cr);
        }
        float hold = UI.meter_hold[c];
        double hf = (hold - min_db) / -min_db;
        if (hf > 0 && hf <= 1) {
            cairo_set_source_rgba(cr, 1, 1, 1, 0.9);
            cairo_rectangle(cr, w * hf - 2, y, 2, bar_h);
            cairo_fill(cr);
        }
    }
}

static void update_meter(const float peak[2])
{
    for (int c = 0; c < 2; c++) {
        float db = to_db(peak[c]);
        if (db > UI.meter_db[c])
            UI.meter_db[c] = db;
        else
            UI.meter_db[c] -= 2.5f;           /* ~25 dB/s release at 100 ms ticks */
        if (db >= UI.meter_hold[c]) {
            UI.meter_hold[c] = db;
            UI.hold_age[c] = 0;
        } else if (++UI.hold_age[c] > 15) {
            UI.meter_hold[c] -= 1.5f;
        }
        if (UI.meter_db[c] < -100.f) UI.meter_db[c] = -100.f;
        if (UI.meter_hold[c] < -100.f) UI.meter_hold[c] = -100.f;
    }
    gtk_widget_queue_draw(UI.meter);
}

static gboolean refresh(gpointer data)
{
    (void)data;
    struct net_stats n;
    struct audio_stats a;
    net_get_stats(&n);
    audio_get_stats(&a);
    char buf[256];

    /* Periodic statistics line on stderr for long unattended runs. */
    static gint64 last_log_us;
    if (opt.log_secs > 0 && n.active) {
        gint64 now_us = g_get_monotonic_time();
        if (now_us - last_log_us >= (gint64)opt.log_secs * G_USEC_PER_SEC) {
            last_log_us = now_us;
            GDateTime *t = g_date_time_new_now_local();
            gchar *ts = g_date_time_format(t, "%H:%M:%S");
            fprintf(stderr, "%s stat pkts %" G_GUINT64_FORMAT " lost %" G_GUINT64_FORMAT " recovered %" G_GUINT64_FORMAT
                    " late %" G_GUINT64_FORMAT " | jit %.2f ms | buf avg %.1f min %.1f ms | drop %" G_GUINT64_FORMAT
                    " ins %" G_GUINT64_FORMAT " resync %" G_GUINT64_FORMAT " under %" G_GUINT64_FORMAT
                    " | rate %+.0f ppm | dev %.1f ms\n",
                    ts, n.packets, n.lost, n.recovered, n.late, n.jitter_ms,
                    a.cfg.rate ? 1000.0 * a.avg_fill_frames / a.cfg.rate : 0.0,
                    a.cfg.rate ? 1000.0 * a.min_fill_frames / a.cfg.rate : 0.0,
                    a.drops, a.inserts, a.resyncs, a.underruns, (a.rate_corr - 1.0) * 1e6, a.device_delay_ms);
            g_free(ts);
            g_date_time_unref(t);
        }
    }

    /* Event log on stderr, so a dropout can be matched to a cause afterwards. */
    static guint64 last_under, last_resync, last_lost;
    if (a.underruns != last_under || a.resyncs != last_resync || n.lost != last_lost) {
        GDateTime *t = g_date_time_new_now_local();
        gchar *ts = g_date_time_format(t, "%H:%M:%S");
        fprintf(stderr, "%s underruns %" G_GUINT64_FORMAT " (+%" G_GUINT64_FORMAT ") resyncs %" G_GUINT64_FORMAT
                " lost %" G_GUINT64_FORMAT " · buffer now %.1f ms min %.1f ms · net jitter %.2f ms\n",
                ts, a.underruns, a.underruns - last_under, a.resyncs, n.lost,
                a.cfg.rate ? 1000.0 * a.fill_frames / a.cfg.rate : 0.0,
                a.cfg.rate ? 1000.0 * a.min_fill_frames / a.cfg.rate : 0.0, n.jitter_ms);
        g_free(ts);
        g_date_time_unref(t);
        last_under = a.underruns; last_resync = a.resyncs; last_lost = n.lost;
    }

    if (!n.listening) {
        gtk_label_set_text(GTK_LABEL(UI.status), "Not listening");
    } else if (!n.active) {
        snprintf(buf, sizeof(buf), "Waiting for a sender on UDP port %d", n.port);
        gtk_label_set_text(GTK_LABEL(UI.status), buf);
    } else {
        snprintf(buf, sizeof(buf), "Streaming · %s", state_name(a.state));
        gtk_label_set_text(GTK_LABEL(UI.status), buf);
    }

    gtk_label_set_text(GTK_LABEL(UI.sender), n.active ? n.sender : "—");

    if (n.active && n.cfg.rate > 0) {
        double pkt_ms = 1000.0 * n.frames_per_packet / n.cfg.rate;
        snprintf(buf, sizeof(buf), "%d Hz · %d ch · %s · %d frames/packet (%.1f ms)",
                 n.cfg.rate, n.cfg.channels, hfs_format_name(n.cfg.format), n.frames_per_packet, pkt_ms);
    } else {
        snprintf(buf, sizeof(buf), "—");
    }
    gtk_label_set_text(GTK_LABEL(UI.format), buf);

    uint64_t total = n.packets + n.lost + n.recovered;
    double loss_pct = total ? 100.0 * n.lost / (double)total : 0.0;
    snprintf(buf, sizeof(buf), "%" G_GUINT64_FORMAT " received · %" G_GUINT64_FORMAT " lost (%.3f %%) · %" G_GUINT64_FORMAT " recovered by resend · %" G_GUINT64_FORMAT " late · %" G_GUINT64_FORMAT " invalid",
             n.packets, n.lost, loss_pct, n.recovered, n.late, n.invalid);
    gtk_label_set_text(GTK_LABEL(UI.packets), buf);

    if (n.phone_volume >= 0)
        snprintf(buf, sizeof(buf), "%.0f kbit/s · jitter %.2f ms · %.0f s · phone volume %.0f %% (%.1f dB)",
                 n.bitrate_kbps, n.jitter_ms, n.session_seconds, n.phone_volume * 100,
                 n.phone_volume > 0 ? 60.0 * log10(n.phone_volume) : -100.0);
    else
        snprintf(buf, sizeof(buf), "%.0f kbit/s · jitter %.2f ms · %.0f s", n.bitrate_kbps, n.jitter_ms, n.session_seconds);
    gtk_label_set_text(GTK_LABEL(UI.bitrate), buf);

    double fill_ms = a.cfg.rate ? 1000.0 * a.fill_frames / a.cfg.rate : 0;
    double min_ms = a.cfg.rate ? 1000.0 * a.min_fill_frames / a.cfg.rate : 0;
    double avg_ms = a.cfg.rate ? 1000.0 * a.avg_fill_frames / a.cfg.rate : 0;
    double target_ms = a.cfg.rate ? 1000.0 * a.target_frames / a.cfg.rate : audio_get_target_ms();
    snprintf(buf, sizeof(buf), "avg %.1f ms · min %.1f ms (margin %.1f ms) · now %.1f ms", avg_ms, min_ms, target_ms, fill_ms);
    gtk_label_set_text(GTK_LABEL(UI.buffer), buf);
    gtk_level_bar_set_value(GTK_LEVEL_BAR(UI.fill_bar), avg_ms > 0 ? MIN(fill_ms / (2 * avg_ms), 1.0) : 0);

    snprintf(buf, sizeof(buf), "%" G_GUINT64_FORMAT " drops · %" G_GUINT64_FORMAT " inserts · %" G_GUINT64_FORMAT " resyncs · %" G_GUINT64_FORMAT " underruns · %" G_GUINT64_FORMAT " overflows",
             a.drops, a.inserts, a.resyncs, a.underruns, a.overflows);
    gtk_label_set_text(GTK_LABEL(UI.corrections), buf);

    if (a.state != AUDIO_IDLE && a.cfg.rate) {
        double q_ms = a.quantum ? 1000.0 * a.quantum / a.cfg.rate : 0;
        snprintf(buf, sizeof(buf), "PipeWire %s · graph %d Hz%s · quantum %d (%.1f ms) · device delay %.1f ms · rate ×%.5f (%+.0f ppm%s)",
                 state_name(a.state), a.graph_rate,
                 a.graph_rate && a.graph_rate != a.cfg.rate ? " (resampling!)" : "",
                 a.quantum, q_ms, a.device_delay_ms, a.rate_corr, (a.rate_corr - 1.0) * 1e6,
                 a.remote_rate ? ", applied on the phone" : "");
        gtk_label_set_text(GTK_LABEL(UI.output), buf);
        double pkt_ms = n.cfg.rate ? 1000.0 * n.frames_per_packet / n.cfg.rate : 0;
        snprintf(buf, sizeof(buf), "≈ %.0f ms on this PC (buffer + device) + %.0f ms packetisation + Wi-Fi + phone capture & pacing (~25 ms)",
                 avg_ms + a.device_delay_ms, pkt_ms);
        gtk_label_set_text(GTK_LABEL(UI.latency), buf);
    } else {
        gtk_label_set_text(GTK_LABEL(UI.output), "—");
        gtk_label_set_text(GTK_LABEL(UI.latency), "—");
    }

    update_meter(a.peak);

    gtk_button_set_label(GTK_BUTTON(UI.dump_button), n.dump_path[0] ? "Stop recording" : "Record to WAV…");
    if (n.dump_path[0]) {
        snprintf(buf, sizeof(buf), "Recording to %s (%.1f s)", n.dump_path,
                 n.cfg.rate ? (double)n.dump_frames / n.cfg.rate : 0.0);
        gtk_widget_set_tooltip_text(UI.dump_button, buf);
    } else {
        gtk_widget_set_tooltip_text(UI.dump_button, NULL);
    }
    return G_SOURCE_CONTINUE;
}

/* ---- settings file ---------------------------------------------------------- */

static gchar *settings_path(void)
{
    return g_build_filename(g_get_user_config_dir(), "hifistream", "receiver.conf", NULL);
}

/* Reads the saved settings into opt; command-line options override them afterwards. */
static void settings_load(void)
{
    gchar *path = settings_path();
    GKeyFile *kf = g_key_file_new();
    if (g_key_file_load_from_file(kf, path, G_KEY_FILE_NONE, NULL)) {
        GError *e = NULL;
        double b = g_key_file_get_double(kf, "receiver", "buffer_ms", &e);
        if (!e && b >= 2 && b <= 100) opt.buffer_ms = b;
        g_clear_error(&e);
        gboolean r = g_key_file_get_boolean(kf, "receiver", "remote_rate", &e);
        if (!e) opt.remote_rate = r;
        g_clear_error(&e);
        int port = g_key_file_get_integer(kf, "receiver", "port", &e);
        if (!e && port > 0 && port < 65536) opt.port = port;
        g_clear_error(&e);
        gchar *target = g_key_file_get_string(kf, "receiver", "target", &e);
        if (!e && target && *target && !opt.target) opt.target = target;   /* PipeWire sink to play to */
        g_clear_error(&e);
    }
    g_key_file_free(kf);
    g_free(path);
}

static void settings_save(void)
{
    gchar *path = settings_path();
    gchar *dir = g_path_get_dirname(path);
    g_mkdir_with_parents(dir, 0755);
    GKeyFile *kf = g_key_file_new();
    g_key_file_load_from_file(kf, path, G_KEY_FILE_KEEP_COMMENTS, NULL);
    g_key_file_set_double(kf, "receiver", "buffer_ms", audio_get_target_ms());
    g_key_file_set_boolean(kf, "receiver", "remote_rate", audio_get_remote_rate());
    g_key_file_set_integer(kf, "receiver", "port", opt.port);
    /* The target sink is kept only if the file already had one or --target was
     * given; without it the stream follows the desktop's default output. */
    if (opt.target)
        g_key_file_set_string(kf, "receiver", "target", opt.target);
    else
        g_key_file_remove_key(kf, "receiver", "target", NULL);
    g_key_file_save_to_file(kf, path, NULL);
    g_key_file_free(kf);
    g_free(dir);
    g_free(path);
}

static void on_remote_rate_toggled(GtkCheckButton *b, gpointer data)
{
    (void)data;
    audio_set_remote_rate(gtk_check_button_get_active(b));
    settings_save();
}

static void on_scale_changed(GtkRange *range, gpointer data)
{
    (void)data;
    audio_set_target_ms(gtk_range_get_value(range));
    settings_save();
}

static gboolean on_listen_toggled(GtkSwitch *sw, gboolean state, gpointer data)
{
    (void)data;
    if (state) {
        int port = atoi(gtk_editable_get_text(GTK_EDITABLE(UI.port_entry)));
        if (port <= 0 || port > 65535) {
            gtk_label_set_text(GTK_LABEL(UI.status), "Invalid port");
            return TRUE;   /* stop the switch from flipping */
        }
        int rc = net_start(port);
        if (rc < 0) {
            char buf[128];
            snprintf(buf, sizeof(buf), "Cannot listen on port %d: %s", port, g_strerror(-rc));
            gtk_label_set_text(GTK_LABEL(UI.status), buf);
            return TRUE;
        }
        gtk_widget_set_sensitive(UI.port_entry, FALSE);
    } else {
        net_stop();
        gtk_widget_set_sensitive(UI.port_entry, TRUE);
    }
    gtk_switch_set_state(sw, state);
    return TRUE;
}

static void on_dump_ready(GObject *src, GAsyncResult *res, gpointer data)
{
    (void)data;
    GFile *f = gtk_file_dialog_save_finish(GTK_FILE_DIALOG(src), res, NULL);
    if (!f)
        return;
    char *path = g_file_get_path(f);
    net_set_dump(path);
    g_free(path);
    g_object_unref(f);
}

static void on_dump_clicked(GtkButton *b, gpointer data)
{
    (void)b; (void)data;
    struct net_stats n;
    net_get_stats(&n);
    if (n.dump_path[0]) {
        net_set_dump(NULL);
        return;
    }
    GtkFileDialog *dlg = gtk_file_dialog_new();
    gtk_file_dialog_set_title(dlg, "Record received audio to WAV");
    gtk_file_dialog_set_initial_name(dlg, "hifistream.wav");
    gtk_file_dialog_save(dlg, GTK_WINDOW(UI.window), NULL, on_dump_ready, NULL);
    g_object_unref(dlg);
}

static GtkWidget *add_row(GtkWidget *grid, int row, const char *title)
{
    GtkWidget *k = gtk_label_new(title);
    gtk_widget_add_css_class(k, "dim-label");
    gtk_label_set_xalign(GTK_LABEL(k), 1.0);
    gtk_widget_set_valign(k, GTK_ALIGN_START);
    GtkWidget *v = gtk_label_new("—");
    gtk_label_set_xalign(GTK_LABEL(v), 0.0);
    gtk_label_set_wrap(GTK_LABEL(v), TRUE);
    gtk_label_set_selectable(GTK_LABEL(v), TRUE);
    gtk_widget_set_hexpand(v, TRUE);
    gtk_grid_attach(GTK_GRID(grid), k, 0, row, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), v, 1, row, 1, 1);
    return v;
}

static void build_window(GtkApplication *app)
{
    UI.window = gtk_application_window_new(app);
    gtk_window_set_title(GTK_WINDOW(UI.window), "HiFi Stream Receiver");
    gtk_window_set_default_size(GTK_WINDOW(UI.window), 680, 840);

    GtkWidget *header = gtk_header_bar_new();
    gtk_window_set_titlebar(GTK_WINDOW(UI.window), header);

    GtkWidget *hbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    GtkWidget *port_label = gtk_label_new("Port");
    UI.port_entry = gtk_entry_new();
    gtk_entry_set_max_length(GTK_ENTRY(UI.port_entry), 5);
    gtk_editable_set_width_chars(GTK_EDITABLE(UI.port_entry), 6);
    char pbuf[16];
    snprintf(pbuf, sizeof(pbuf), "%d", opt.port);
    gtk_editable_set_text(GTK_EDITABLE(UI.port_entry), pbuf);
    UI.listen_switch = gtk_switch_new();
    gtk_widget_set_valign(UI.listen_switch, GTK_ALIGN_CENTER);
    g_signal_connect(UI.listen_switch, "state-set", G_CALLBACK(on_listen_toggled), NULL);
    gtk_box_append(GTK_BOX(hbox), port_label);
    gtk_box_append(GTK_BOX(hbox), UI.port_entry);
    gtk_box_append(GTK_BOX(hbox), gtk_label_new("Listen"));
    gtk_box_append(GTK_BOX(hbox), UI.listen_switch);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(header), hbox);

    UI.dump_button = gtk_button_new_with_label("Record to WAV…");
    g_signal_connect(UI.dump_button, "clicked", G_CALLBACK(on_dump_clicked), NULL);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(header), UI.dump_button);

    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_widget_set_margin_top(vbox, 16);
    gtk_widget_set_margin_bottom(vbox, 16);
    gtk_widget_set_margin_start(vbox, 16);
    gtk_widget_set_margin_end(vbox, 16);
    gtk_window_set_child(GTK_WINDOW(UI.window), vbox);

    UI.status = gtk_label_new("");
    gtk_widget_add_css_class(UI.status, "title-2");
    gtk_label_set_xalign(GTK_LABEL(UI.status), 0.0);
    gtk_box_append(GTK_BOX(vbox), UI.status);

    UI.meter = gtk_drawing_area_new();
    gtk_widget_set_size_request(UI.meter, -1, 26);
    gtk_drawing_area_set_draw_func(GTK_DRAWING_AREA(UI.meter), meter_draw, NULL, NULL);
    gtk_box_append(GTK_BOX(vbox), UI.meter);
    UI.meter_db[0] = UI.meter_db[1] = UI.meter_hold[0] = UI.meter_hold[1] = -100.f;

    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 8);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 14);
    gtk_box_append(GTK_BOX(vbox), grid);
    int r = 0;
    UI.sender      = add_row(grid, r++, "Sender");
    UI.format      = add_row(grid, r++, "Format");
    UI.packets     = add_row(grid, r++, "Packets");
    UI.bitrate     = add_row(grid, r++, "Network");
    UI.buffer      = add_row(grid, r++, "Buffer");
    UI.fill_bar    = gtk_level_bar_new();
    gtk_widget_set_hexpand(UI.fill_bar, TRUE);
    gtk_grid_attach(GTK_GRID(grid), UI.fill_bar, 1, r++, 1, 1);
    UI.corrections = add_row(grid, r++, "Corrections");
    UI.output      = add_row(grid, r++, "Output");
    UI.latency     = add_row(grid, r++, "Latency");

    GtkWidget *sbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *slabel = gtk_label_new("Safety margin (ms): minimum audio kept buffered. Latency adapts to the measured jitter on top of this; raise it if you hear dropouts.");
    gtk_widget_add_css_class(slabel, "dim-label");
    gtk_label_set_xalign(GTK_LABEL(slabel), 0.0);
    gtk_label_set_wrap(GTK_LABEL(slabel), TRUE);
    UI.scale = gtk_scale_new_with_range(GTK_ORIENTATION_HORIZONTAL, 2, 100, 1);
    gtk_scale_set_draw_value(GTK_SCALE(UI.scale), TRUE);
    gtk_scale_set_value_pos(GTK_SCALE(UI.scale), GTK_POS_RIGHT);
    gtk_range_set_value(GTK_RANGE(UI.scale), opt.buffer_ms);
    for (int ms = 10; ms <= 100; ms += 10)
        gtk_scale_add_mark(GTK_SCALE(UI.scale), ms, GTK_POS_BOTTOM, ms % 20 == 0 ? g_strdup_printf("%d", ms) : NULL);
    g_signal_connect(UI.scale, "value-changed", G_CALLBACK(on_scale_changed), NULL);
    gtk_box_append(GTK_BOX(sbox), slabel);
    gtk_box_append(GTK_BOX(sbox), UI.scale);
    gtk_box_append(GTK_BOX(vbox), sbox);

    GtkWidget *rr = gtk_check_button_new_with_label("Rate matching on the phone (this PC never resamples; lossless with a rooted phone, what a microcontroller receiver would do)");
    gtk_check_button_set_active(GTK_CHECK_BUTTON(rr), opt.remote_rate);
    g_signal_connect(rr, "toggled", G_CALLBACK(on_remote_rate_toggled), NULL);
    gtk_box_append(GTK_BOX(vbox), rr);

    GtkWidget *hint = gtk_label_new("Phone: HiFi Stream app → set this PC's address (or tap Discover) → Start. "
                                    "Turn the phone's media volume to 0 to hear only the PC.");
    gtk_widget_add_css_class(hint, "dim-label");
    gtk_label_set_wrap(GTK_LABEL(hint), TRUE);
    gtk_label_set_xalign(GTK_LABEL(hint), 0.0);
    gtk_box_append(GTK_BOX(vbox), hint);

    g_timeout_add(100, refresh, NULL);
    gtk_window_present(GTK_WINDOW(UI.window));

    gtk_switch_set_active(GTK_SWITCH(UI.listen_switch), TRUE);
}

/* ---- headless ---------------------------------------------------------------- */

static gboolean headless_tick(gpointer data)
{
    (void)data;
    struct net_stats n;
    struct audio_stats a;
    net_get_stats(&n);
    audio_get_stats(&a);
    if (!n.active) {
        printf("waiting for sender on port %d\r", n.port);
    } else {
        double fill_ms = a.cfg.rate ? 1000.0 * a.avg_fill_frames / a.cfg.rate : 0;
        double min_ms = a.cfg.rate ? 1000.0 * a.min_fill_frames / a.cfg.rate : 0;
        printf("%s %d Hz %s | %s | pkts %" G_GUINT64_FORMAT " lost %" G_GUINT64_FORMAT " recovered %" G_GUINT64_FORMAT " late %" G_GUINT64_FORMAT
               " | %.0f kbit/s jit %.2f ms | buf avg %.1f min %.1f ms | drop %" G_GUINT64_FORMAT " ins %" G_GUINT64_FORMAT
               " resync %" G_GUINT64_FORMAT " under %" G_GUINT64_FORMAT " | rate %+.0f ppm | dev %.1f ms | peak %.1f/%.1f dB | vol %.0f%%\n",
               n.sender, n.cfg.rate, hfs_format_name(n.cfg.format), state_name(a.state),
               n.packets, n.lost, n.recovered, n.late, n.bitrate_kbps, n.jitter_ms, fill_ms, min_ms,
               a.drops, a.inserts, a.resyncs, a.underruns, (a.rate_corr - 1.0) * 1e6, a.device_delay_ms,
               to_db(a.peak[0]), to_db(a.peak[1]), n.phone_volume >= 0 ? n.phone_volume * 100 : 100.0);
    }
    fflush(stdout);
    return G_SOURCE_CONTINUE;
}

static gboolean on_signal(gpointer data)
{
    g_application_quit(G_APPLICATION(data));
    return G_SOURCE_REMOVE;
}

static void on_activate(GtkApplication *app, gpointer data)
{
    (void)data;
    g_unix_signal_add(SIGINT, on_signal, app);
    g_unix_signal_add(SIGTERM, on_signal, app);
    if (opt.headless) {
        int rc = net_start(opt.port);
        if (rc < 0) {
            fprintf(stderr, "cannot listen on port %d: %s\n", opt.port, g_strerror(-rc));
            g_application_quit(G_APPLICATION(app));
            return;
        }
        g_application_hold(G_APPLICATION(app));
        g_timeout_add(1000, headless_tick, NULL);
    } else {
        build_window(app);
    }
    if (opt.dump)
        net_set_dump(opt.dump);
}

static void on_shutdown(GApplication *app, gpointer data)
{
    (void)app; (void)data;
    net_stop();
    audio_shutdown();
}

static int on_handle_local_options(GApplication *app, GVariantDict *dict, gpointer data)
{
    (void)app; (void)dict; (void)data;
    struct audio_options ao = { .quantum = opt.quantum, .force_rate = opt.force_rate, .target = opt.target };
    if (audio_init(&ao) < 0) {
        fprintf(stderr, "cannot start PipeWire\n");
        return 1;
    }
    if (opt.local_rate)
        opt.remote_rate = FALSE;
    audio_set_target_ms(opt.buffer_ms);
    audio_set_remote_rate(opt.remote_rate);
    return -1;   /* continue */
}

int main(int argc, char **argv)
{
    settings_load();     /* saved slider / check-box values; command-line options override */
    GtkApplication *app = gtk_application_new("io.github.hifistream.Receiver", G_APPLICATION_NON_UNIQUE);
    g_application_add_main_option_entries(G_APPLICATION(app), entries);
    g_application_set_option_context_summary(G_APPLICATION(app),
        "Receives lossless PCM audio streamed over Wi-Fi from the HiFi Stream Android app\n"
        "and plays it through PipeWire.");
    g_signal_connect(app, "handle-local-options", G_CALLBACK(on_handle_local_options), NULL);
    g_signal_connect(app, "activate", G_CALLBACK(on_activate), NULL);
    g_signal_connect(app, "shutdown", G_CALLBACK(on_shutdown), NULL);
    int status = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    return status;
}
