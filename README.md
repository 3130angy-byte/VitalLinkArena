# VitalLink Arena

*Español: [LEEME.md](LEEME.md)*

Your **Vital Bracelet** Digimon lives on your PC desktop, battles, and joins an
online lobby to fight other players' Digimon. When you are done, you **retire**
it and the program gives you a VS DIM with the result of its battles, ready to
send back to your Vital Bracelet.

> Non-profit fan project, **not affiliated with or endorsed by Bandai**.
> **Version v0.4.0-beta.1** (public beta).
> The game itself is in Spanish.

## Download and play (Windows)

1. Download `VitalLink Arena v0.4.0-beta.1.zip` from **[Releases](../../releases)**.
   Java is included: nothing else to install.
2. Unzip it and open `VitalLink Arena\VitalLink Arena.exe`.
   If Windows says *"Windows protected your PC"*: **More info → Run anyway**
   (the program is not digitally signed).
3. Type your user name and, in *Servidor* (server), the Tailscale IP your host
   gave you (`100.x` is written there as an example). Without one, the game
   still starts with every local feature.
4. **Cargar mi Digimon (VS DIM)** (load my Digimon): pick your Digimon's `.bin`,
   extracted from the Vital Bracelet with your card reader, and type how many
   days old it was. **Back up that file first.** The species is read
   automatically from the name sprite; you can correct it.

Requirements: 64-bit Windows 10 or 11.

## Your account for online play (2 minutes)

The VS Online lobby runs through [Tailscale](https://tailscale.com/), a free
private network that connects your PC to the host's. **That account is your
registration**: there is no other account or password.

1. Ask the host for their **invite link** (on Discord, for example).
2. Open it and **create your Tailscale account by signing in with Google,
   Microsoft, GitHub or Apple** (no new password).
3. **Install the Tailscale app** on your PC, sign in with that same account and
   keep it **connected** whenever you play.
4. **Accept the invite**: you will see the host's machine with its `100.x.x.x`
   IP. Type that IP in *Servidor* when you open the game.
5. In the game, **BATALLA → VS ONLINE**: the first time you **ask for
   permission** and wait in the lobby until the host accepts you (they see your
   name and your Tailscale account). From then on you are registered: you get in
   directly, nobody else can use your name, and your account remembers it even
   if you type another one or join from another machine. If the host does not
   answer within 3 minutes, your request stays saved; come back later.

Without an account you can still use everything local (desktop, random battle,
ARENA against the computer, Lab).

## Features

Left click = move your Digimon. Right click = menu.

- **Digimon on your desktop**: arrives through a portal and walks around (still,
  along the taskbar, or free). It reacts to what happens (appearing, winning,
  losing, going to the lobby...) with **predefined phrases** based on its
  attribute, stage and Activity Type. You can have 2 (slot 1 and slot 2).
- **DIGIMON**: stats, attribute, C/B/A/S rank by trophies (10 / 30 / 70 / 120)
  and the bonus they add.
- **BATALLA → ALEATORIA** (random battle): a VS DIM only holds your own Digimon,
  so rivals come from **regular DIM cards** (`.bin`) on your PC. Add them in
  *LAB → RIVALES* (a folder or single files). Your Digimon must be Child or
  higher.
- **BATALLA → ARENA 2 VS 2** (inspired by the Vital Bracelet Arena app, with our
  own rules and art): your slot 1 and slot 2 against the computer or another
  online player. *ATTACK* = tap the numbers in order for 10 s (the combo raises
  your attack; 10+ fires the BIG ATTACK); when attacked, choose *DEFENSE* (stop
  the bar on the shield) or *PROTECT* (your partner takes the hit); *W-ATTACK*
  when the gauge is full; *GUTS* sometimes saves you with 1 HP. It does not
  count for the Vital Bracelet record.
- **BATALLA → VS ONLINE**: a lobby where your Digimon is the player (move it by
  clicking).
  - *Batalla oficial* NPC: set yourself available and challenge others (15 s to
    accept). The server computes the fight once and both players see the same
    result. **Batalla Libre** = stats + trophy bonus (every 10 = +50% DP,
    +25% HP, +1 AP; capped at 120). **Batalla Original** = DIM stats only.
    **ARENA 2 vs 2** against another player.
  - *Recepción de torneos* NPC (tournaments): anyone can create one (Original,
    Libre or ARENA 2 vs 2; 4, 8 or 16 slots; up to 2 at once). The organizer
    accepts each entrant by hand and starts it whenever they want. Single
    elimination, an "Are you READY?" check before each match (60 s), a rematch
    on a draw and a coin toss on the third. Closing the lobby drops you from that
    tournament (if your connection drops, you have 1 minute to return).
  - **Champion's prize**: the winner's Digimon can be shown at **double size in
    pixel art** on the desktop, in the lobby and in battles. Toggle it in its
    menu (*GRANDE: SÍ / NO*); it is saved in its capsule. In ARENA, both team
    members win it.
  - The PC in the lobby opens your Lab to change your team.
- **LAB** (Laboratory): your Digimon saved as capsules (copies of your VS DIMs),
  a Digidex of species seen, battle history and rivals. Swap who is on the
  desktop without retiring them.
- **SONIDO** (sound): the Vital Bracelet sound effects are **not included**
  (they belong to Bandai). If you have your own as `1.mp3` ... `27.mp3`, put
  them in `DigimonProjectData\sounds`.

## Retiring: back to the Vital Bracelet

Every random and official battle counts. **RETIRAR** (retire) shows a summary;
if you accept, the Digimon leaves through a portal and the `devoluciones`
folder opens with a new VS DIM carrying a **battle report** (the same format a
VB writes after fighting a card): when you load it back, the Vital Bracelet
applies the reward or penalty itself (tested on a real VB). The pending record
is kept in the capsule even if you close the program. The original VS DIM is
never modified: send the **new** one to your Vital Bracelet, with your backup at
hand.

## Where your data lives

In `D:\DigimonProjectData` if you have a D: drive; otherwise in
`DigimonProjectData` inside your Windows user folder: `devoluciones`,
`laboratorio` and `config` (settings as `.properties` files).

## Hosting your own server (from source)

The download does not include a server. From the source:
`.\gradlew.bat runServer` (port 7777). Console: `jugadores` (players),
`expulsar <n>` (kick), `solicitudes` (requests), `aceptar <n>` (accept),
`rechazar <n>` (reject), `apagar` (shut down), `ayuda` (help). The server asks
your PC's Tailscale (`tailscale whois`) which account each connection comes
from; your own PC always gets in. Files (in `DigimonProjectData\config`):
`lista-acceso.txt` (who has permission and their account) and
`solicitudes-acceso.txt` (pending requests, expire after 24 h). Removing someone
from the list kicks them from the lobby within seconds. A connection that does
not identify itself within 10 s is dropped, and an IP with many rejected
requests or names in a row is blocked for 10 minutes. Rules (ranks, tournaments,
ARENA) live in the `.properties` files of that folder. Never expose port 7777
with `tailscale serve` or `funnel`.

## For developers: reading and writing VS DIM files

A **VS DIM** (the file a VB produces when you extract a Digimon) **is not a
regular DIM card**, and known libraries cannot open it. In
[`src/main/java/org/example/dim/`](src/main/java/org/example/dim/) there is a
custom reader (`VsDimReader`, `VsDimData`) that extracts the transferred
Digimon's sprites, its stats table, slot, Vital Values and trophies, and a
writer (`VsDimWriter`) that recomputes the checksum and writes the battle
report. The format is documented in the code comments (in Spanish). **Feel free
to use it** (MIT license).

## Building from source

Requires JDK 17 (Gradle is fetched by the wrapper).

```bash
.\gradlew.bat run           # the game
.\gradlew.bat runServer     # VS Online server with console
.\gradlew.bat packageGame   # Windows package with Java included (build\dist)
```

The Digimon's phrases live in [`banco-frases-borrador/`](banco-frases-borrador/)
(format in its `LEEME.txt`, in Spanish).

## License

The project's **original code and art** are under the **MIT** license
([`LICENSE`](LICENSE)): you may use, copy, modify and redistribute them freely,
even in other projects, as long as you keep the copyright notice.

The license **does not cover** third-party material, which still belongs to its
owners: the *Digimon* and *Vital Bracelet* trademarks and the firmware attack
sprites (© Bandai), and other authors' libraries. Details in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Credits

- [VB-DIM-Reader](https://github.com/cfogrady/VB-DIM-Reader) by Christopher Milne-O'Grady (MIT), to read DIM cards.
- DIM format and combat checked against DIM-Modifier and VitalWear.
- Trophy bonus based on the *Digimon Link Pendulum* mod.

## Legal notice

*Digimon* and *Vital Bracelet* are registered trademarks of Bandai; they are
mentioned here only to say which device the program works with. This project is
not affiliated with, sponsored or endorsed by Bandai. Each Digimon's sprites are
read from the user's own files; the program does not include them. If Bandai asks
for any material to be removed, it will be removed.
