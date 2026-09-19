# HiFi Stream Receiver — GTK4 + PipeWire
CC      ?= gcc
PKGS     = gtk4 libpipewire-0.3
CFLAGS  ?= -O2 -g
CFLAGS  += -std=gnu11 -Wall -Wextra -Wno-unused-parameter $(shell pkg-config --cflags $(PKGS))
LDLIBS  += $(shell pkg-config --libs $(PKGS)) -lpthread -lm
PREFIX  ?= /usr/local

SRC = src/main.c src/audio.c src/net.c
OBJ = $(SRC:.c=.o)
BIN = hifistream-receiver

all: $(BIN)

$(BIN): $(OBJ)
	$(CC) -o $@ $(OBJ) $(LDLIBS)

src/%.o: src/%.c src/audio.h src/net.h src/protocol.h
	$(CC) $(CFLAGS) -c -o $@ $<

install: $(BIN)
	install -Dm755 $(BIN) $(DESTDIR)$(PREFIX)/bin/$(BIN)
	install -Dm644 hifistream-receiver.desktop $(DESTDIR)$(PREFIX)/share/applications/hifistream-receiver.desktop
	install -Dm644 hifistream-receiver.svg $(DESTDIR)$(PREFIX)/share/icons/hicolor/scalable/apps/hifistream-receiver.svg

# Per-user install: no root, shows up in the desktop's application menu.
install-user: $(BIN)
	install -Dm755 $(BIN) $(HOME)/.local/bin/$(BIN)
	install -Dm644 hifistream-receiver.svg $(HOME)/.local/share/icons/hicolor/scalable/apps/hifistream-receiver.svg
	sed 's|^Exec=.*|Exec=$(HOME)/.local/bin/$(BIN)|' hifistream-receiver.desktop > $(HOME)/.local/share/applications/hifistream-receiver.desktop
	-update-desktop-database $(HOME)/.local/share/applications 2>/dev/null
	-gtk-update-icon-cache -q $(HOME)/.local/share/icons/hicolor 2>/dev/null

uninstall-user:
	rm -f $(HOME)/.local/bin/$(BIN) $(HOME)/.local/share/applications/hifistream-receiver.desktop \
	      $(HOME)/.local/share/icons/hicolor/scalable/apps/hifistream-receiver.svg

clean:
	rm -f $(OBJ) $(BIN)

.PHONY: all install install-user uninstall-user clean
