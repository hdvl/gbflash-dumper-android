# Third-party notices

GBFlash Dumper (Android) is licensed under the MIT License — see `LICENSE`. This file covers
everything else: third-party code and assets it bundles or depends on, and a fuller account of
its debt to FlashGBX than a license technically requires.

## FlashGBX (GPL-3.0) — protocol reference

This app talks to the GBFlash over the same serial protocol as
[FlashGBX](https://github.com/lesserkuma/FlashGBX) by Lesserkuma, the reference software for
this hardware and, as far as could be found, the only place that protocol is documented. This
app's protocol layer (`GbxDevice.kt`, `DmgMapper.kt`, `AgbSaveType.kt`, and related files) was
built by reading FlashGBX's `hw_GBFlash.py` and `LK_Device.py` — command bytes, firmware
variable IDs, and the exact order operations have to happen in (e.g. `TRANSFER_SIZE` before
`ADDRESS`, or the JEDEC-ID sequence for AGB save-flash chips) all come from that reading, since
they're facts about how the hardware behaves and have to match to work at all. No FlashGBX
source was copied, transcribed, or mechanically translated — the Kotlin here is independently
written, its own structure and naming throughout, even where the shape of the problem (e.g. one
class per DMG mapper, mirroring FlashGBX's own `Mapper.py` layout) ends up similar because
there's only so many reasonable ways to model the same hardware.

FlashGBX itself is GPL-3.0; this app is not a derivative work of it (no GPL code in this
repository) and is separately MIT-licensed. Lesserkuma, FlashGBX's author, was asked and gave
explicit permission for this app to be published.

This is a good-faith account of how the app was built, not a legal opinion — read the code
yourself if this matters for your use case.

## usb-serial-for-android (MIT)

`app/src/main/java/com/gbflash/dumper/serial/GbFlashSerialDriver.kt` is a modified port of
`Ch34xSerialDriver.java` from
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) by mike wakerly and
contributors (MIT). The original copyright notice and permission text are reproduced at the top
of that file, along with what was changed and why — in short, several cheap CH340 clone chips
answer an init-verification byte differently from genuine WCH silicon, which the stock driver
treats as fatal even though the chip works fine otherwise; this patched copy downgrades those
checks to warnings.

The rest of the app depends on the unmodified library as a normal Gradle dependency
(`com.github.mik3y:usb-serial-for-android`), also MIT.

## Press Start 2P (SIL OFL 1.1)

`app/src/main/res/font/press_start_2p.ttf` is the "Press Start 2P" typeface by The Press Start
2P Project Authors, used for headings. Full license text:
`app/src/main/assets/licenses/press_start_2p_OFL.txt`.

## Built with AI assistance

Most of this app, including the Android/USB transport debugging needed to get it actually
talking to real hardware, was built with AI coding assistance, working from the FlashGBX source
described above. See `README.md` / `README.en.md`.
