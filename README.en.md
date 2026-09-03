# GBFlash Dumper (Android)

*[🇫🇷 Lire en français](README.md)*

A small Android app to dump Game Boy / Game Boy Color / Game Boy Advance cartridges (ROM +
save) using a [GBFlash](https://github.com/simonkwng/GBFlash) USB dumper, straight from your
phone via USB-OTG — no PC needed.

**Heads up before you use this:** this is a vibe-coded side project. I'm not a developer — I
have no real coding background, and this app was built almost entirely with AI assistance. I
can't personally vouch for the Kotlin/Android/USB code beyond "it works on my hardware, on the
cartridges I tested". Treat it as a hobby project, not production software — use at your own
risk, and expect rough edges or things that don't work on your setup.

## What it does

- Detects a cartridge over USB-OTG — Game Boy / Color (DMG) or Game Boy Advance (AGB)
- Dumps the ROM
- Dumps the save, for the common mappers (DMG: MBC1/MBC2/MBC3/MBC5 and no-mapper carts; AGB:
  SRAM/FRAM, FLASH, EEPROM)
- Confirmed working end-to-end on real hardware — dumps load correctly in an emulator

Not supported: writing or flashing anything back to the cartridge (read-only by design), and
some rarer/unofficial mappers.

## Credits & license

This app only exists because [FlashGBX](https://github.com/lesserkuma/FlashGBX) by Lesserkuma
documents the GBFlash's serial protocol — its source was the reference this app's protocol
handling was built from. Big thanks to Lesserkuma, who was also kind enough to give the go-ahead
to post this app publicly. It also relies on
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (with one small local
patch) and talks to the [GBFlash](https://github.com/simonkwng/GBFlash) open-hardware dumper.

MIT license — see `LICENSE`. Third-party code and assets keep their own license; see
`NOTICE.md` for the full details.
