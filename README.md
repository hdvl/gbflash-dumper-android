# GBFlash Dumper (Android)

*[🇬🇧 Read in English](README.en.md)*

Une petite app Android pour dumper des cartouches Game Boy / Game Boy Color / Game Boy
Advance (ROM + sauvegarde) via un dumper USB [GBFlash](https://github.com/simonkwng/GBFlash),
directement depuis un téléphone via USB-OTG — sans PC.

**À savoir avant d'utiliser ça :** c'est un projet perso "vibe-codé". Je ne suis pas développeur
— je n'ai aucune vraie expérience en code, et cette appli a été construite quasi entièrement avec
l'aide d'une IA. Je ne peux pas garantir personnellement la qualité du code Kotlin/Android/USB.
À considérer comme un projet de loisir, rien d'autre — ça marche sur mon matériel, avec les cartouches
que j'ai testées, alors pas de raison que ça bug chez vous. Cela dit, utilisation à vos risques
et périls, et il peut y avoir des trucs qui ne marchent pas de votre côté.

## Ce que ça fait

- Détecte une cartouche via USB-OTG — Game Boy / Color (DMG) ou Game Boy Advance (AGB)
- Dump la ROM
- Dump la sauvegarde, pour les mappers courants (DMG : MBC1/MBC2/MBC3/MBC5 et cartouches sans
  mapper ; AGB : SRAM/FRAM, FLASH, EEPROM)
- Confirmé fonctionnel de bout en bout sur du vrai matériel — les dumps se chargent
  correctement dans un émulateur

Pas supporté : écrire ou flasher quoi que ce soit vers la cartouche (lecture seule par
conception), et certains mappers plus rares/non-officiels.

## Crédits & licence

Cette appli n'existe que grâce à [FlashGBX](https://github.com/lesserkuma/FlashGBX), 
qui documente le protocole série du GBFlash — son code source a servi de référence
pour construire la gestion du protocole de cette appli. Un grand merci à Lesserkuma, qui m'a
gentiment donné son feu vert pour publier cette app. Elle s'appuie aussi sur
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (avec un petit patch
local) et communique avec le [GBFlash](https://github.com/simonkwng/GBFlash), le dumper à
matériel ouvert.

Licence MIT — voir `LICENSE`. Le code et les ressources tierces gardent leur propre licence ;
voir `NOTICE.md` pour le détail complet.
