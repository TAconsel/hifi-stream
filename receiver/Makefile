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

clean:
	rm -f $(OBJ) $(BIN)

.PHONY: all install clean
