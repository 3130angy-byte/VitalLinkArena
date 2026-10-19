# VitalLink Arena

*English: [README.md](README.md)*

Tu Digimon del **Vital Bracelet** vive en el escritorio de tu PC, pelea y entra
a una sala en línea para enfrentarse a los Digimon de otros jugadores. Al final
lo **retiras** y el programa te da una VS DIM con el resultado de sus batallas
para devolverlo al Vital Bracelet.

> Proyecto de fans sin fines de lucro, **no afiliado ni respaldado por Bandai**.
> **Versión v0.4.0-beta.1** (beta pública).

## Descargar y jugar (Windows)

1. Descarga `VitalLink Arena v0.4.0-beta.1.zip` desde **[Releases](../../releases)**.
   Trae Java incluido: no hay que instalar nada más.
2. Descomprime y abre `VitalLink Arena\VitalLink Arena.exe`.
   Si Windows avisa *"Windows protegió su PC"*: **Más información → Ejecutar de todas formas**
   (el programa no tiene firma digital).
3. Escribe tu nombre de usuario y, en *Servidor*, la IP de Tailscale que te pase
   el anfitrión (viene escrito `100.x` como ejemplo). Si no tienes una, el juego
   arranca igual con todas las funciones locales.
4. **Cargar mi Digimon (VS DIM)**: elige el `.bin` de tu Digimon, sacado del
   Vital Bracelet con tu lector de tarjetas, y escribe cuántos días tenía.
   **Guarda antes una copia de ese archivo.** La especie se lee sola del sprite
   del nombre; puedes corregirla.

Requisitos: Windows 10 u 11 de 64 bits.

## Tu cuenta para jugar en línea (2 minutos)

Para entrar a la sala del VS Online necesitas una cuenta de
[Tailscale](https://tailscale.com/), una red privada gratuita que conecta tu PC
con la del anfitrión. **Esa cuenta es tu registro**: no hay otra cuenta ni otra
contraseña.

1. Pídele al anfitrión su **enlace de invitación** (por ejemplo, por Discord).
2. Ábrelo y **crea tu cuenta de Tailscale entrando con Google, Microsoft, GitHub
   o Apple** (no se crea una contraseña nueva).
3. **Instala la app de Tailscale** en tu PC, inicia sesión con esa misma cuenta y
   déjala **conectada** cada vez que juegues.
4. **Acepta la invitación**: verás el equipo del anfitrión con su IP
   `100.x.x.x`. Esa es la IP que escribes en *Servidor* al abrir el juego.
5. En el juego, **BATALLA → VS ONLINE**: la primera vez **pides permiso** y
   esperas en la sala hasta que el anfitrión te acepte (ve tu nombre y tu cuenta
   de Tailscale). Desde ahí quedas registrado: entras directo, nadie más puede
   usar tu nombre y tu cuenta lo recuerda aunque escribas otro o entres desde
   otro equipo. Si no responde en 3 minutos, tu pedido queda guardado; vuelve
   a entrar más tarde.

Sin cuenta igual puedes usar todo lo local (escritorio, Batalla aleatoria,
ARENA contra la máquina, Laboratorio).

## Qué se puede hacer

Clic izquierdo = mover a tu Digimon. Clic derecho = menú.

- **Digimon en el escritorio**: llega por un portal y pasea (quieto, por la
  barra o libre). Reacciona a lo que pasa (aparecer, ganar, perder, ir a la
  sala...) con **frases predefinidas** según su atributo, etapa y Activity Type.
  Puedes tener 2 (puesto 1 y puesto 2).
- **DIGIMON**: stats, atributo, rango C/B/A/S según sus trofeos (10 / 30 / 70 /
  120) y el bono que le suman.
- **BATALLA → ALEATORIA**: la VS DIM solo trae a tu Digimon, así que los rivales
  salen de **DIM cards normales** (`.bin`) que tengas en tu PC. Agrégalas en
  *LAB → RIVALES* (una carpeta o archivos sueltos). Tu Digimon debe ser Child o
  superior.
- **BATALLA → ARENA 2 VS 2** (inspirada en la app Vital Bracelet Arena, con
  reglas y arte propios): tu puesto 1 y tu puesto 2 contra la máquina o contra
  otro jugador en línea. *ATTACK* = toca los números en orden durante 10 s (el
  combo sube tu ataque; con 10 o más sale el BIG ATTACK); al ser atacado eliges
  *DEFENSE* (detén la barra en el escudo) o *PROTECT* (tu compañero recibe el
  golpe); *W-ATTACK* con el medidor lleno; *GUTS* a veces te salva con 1 HP. No
  cuenta para el récord del Vital Bracelet.
- **BATALLA → VS ONLINE**: una sala donde tu Digimon es el jugador (lo mueves
  con clic).
  - NPC *Batalla oficial*: te pones disponible y retas a otros (15 s para
    aceptar). El servidor calcula la pelea una sola vez y ambos ven lo mismo.
    **Batalla Libre** = stats + bono por trofeos (cada 10 = +50 % DP, +25 % HP,
    +1 AP; tope 120). **Batalla Original** = solo los stats de la DIM.
    **ARENA 2 vs 2** contra otro jugador.
  - NPC *Recepción de torneos*: cualquiera crea uno (Original, Libre o ARENA 2
    vs 2; 4, 8 o 16 cupos; hasta 2 a la vez). El organizador acepta a mano a cada
    inscrito y lo empieza cuando quiere. Eliminación directa, "¿Estás LISTO?"
    antes de cada combate (60 s), revancha en el empate y sorteo al tercero. Si
    cierras la sala quedas fuera de ese torneo (si se corta tu internet, tienes
    1 minuto para volver).
  - **Premio del campeón**: su Digimon puede lucirse al **doble de tamaño en
    pixel art** en el escritorio, la sala y las peleas. Se prende y se apaga en
    su menú (casilla *GRANDE: SÍ / NO*) y se guarda en su cápsula. En la ARENA
    lo ganan los 2 del equipo.
  - La PC de la sala abre tu Laboratorio para cambiar tu equipo.
- **LAB** (Laboratorio): tus Digimon guardados como cápsulas (copias de tus VS
  DIM), Digidex de especies vistas, historial de batallas y rivales. Cambias
  quién está en el escritorio sin retirarlo.
- **SONIDO**: los efectos de sonido del Vital Bracelet **no vienen con el
  programa** (son de Bandai). Si tienes los tuyos como `1.mp3` ... `27.mp3`,
  ponlos en `DigimonProjectData\sounds`.

## Retirar y devolverlo al Vital Bracelet

Cada Batalla aleatoria y Oficial cuenta. Al **RETIRAR** ves el resumen y, si
aceptas, el Digimon se va por un portal y se abre la carpeta `devoluciones` con
una VS DIM nueva que lleva el **reporte de batalla** (el mismo formato que
escribe un VB al pelear contra una tarjeta): al devolverla, el Vital Bracelet
entrega él mismo la recompensa o el castigo (probado en un VB real). El récord
pendiente se guarda en la cápsula aunque cierres el programa. La VS DIM original
no se toca: pasa al Vital Bracelet la **nueva**, con tu copia de seguridad a mano.

## Dónde quedan tus datos

En `D:\DigimonProjectData` si tienes disco D:, y si no, en la carpeta
`DigimonProjectData` de tu usuario de Windows: `devoluciones`, `laboratorio` y
`config` (ajustes en archivos `.properties`).

## Tener tu propio servidor (desde el código)

El descargable no trae servidor. Desde el código: `.\gradlew.bat runServer`
(puerto 7777). Consola: `jugadores`, `expulsar <número>`, `solicitudes`,
`aceptar <número>`, `rechazar <número>`, `apagar`, `ayuda`. El servidor le
pregunta a Tailscale de tu PC (`tailscale whois`) de qué cuenta viene cada
conexión; desde tu propia PC siempre entras. Archivos (en
`DigimonProjectData\config`): `lista-acceso.txt` (quién tiene permiso y su
cuenta) y `solicitudes-acceso.txt` (pedidos pendientes, vencen a las 24 h).
Retirar a alguien de la lista lo saca de la sala en unos segundos. Una conexión
que no se identifica en 10 s se corta, y una IP con muchos pedidos o nombres
rechazados seguidos queda bloqueada 10 minutos. Las reglas (rangos, torneos,
ARENA) se cambian en los `.properties` de esa carpeta. Nunca publiques el puerto
7777 con `tailscale serve` ni `funnel`.

## Para desarrolladores: leer y escribir VS DIM

La **VS DIM** (el archivo que genera el VB al extraer un Digimon) **no es una
DIM card normal** y las librerías conocidas no pueden abrirla. En
[`src/main/java/org/example/dim/`](src/main/java/org/example/dim/) hay un lector
propio (`VsDimReader`, `VsDimData`) que obtiene los sprites del Digimon
transferido, su tabla de stats, el slot, los Vital Values y los trofeos, y un
escritor (`VsDimWriter`) que recalcula el checksum y escribe el reporte de
batalla. El formato está documentado en los comentarios. **Úsalo libremente**
(licencia MIT).

## Compilar desde el código

Requiere JDK 17 (Gradle se descarga con el wrapper).

```bash
.\gradlew.bat run           # el juego
.\gradlew.bat runServer     # servidor VS Online con consola
.\gradlew.bat packageGame   # paquete para Windows con Java incluido (build\dist)
```

Las frases del Digimon están en [`banco-frases-borrador/`](banco-frases-borrador/)
(formato en su `LEEME.txt`).

## Licencia

El **código y el arte original** del proyecto están bajo licencia **MIT**
([`LICENSE`](LICENSE)): puedes usarlos, copiarlos, modificarlos y
redistribuirlos libremente, incluso en otros proyectos, siempre que conserves el
aviso de copyright.

La licencia **no cubre** material de terceros, que sigue perteneciendo a sus
dueños: las marcas *Digimon* y *Vital Bracelet* y los sprites de ataque del
firmware (© Bandai), y las librerías de otros autores. Detalle en
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Créditos

- [VB-DIM-Reader](https://github.com/cfogrady/VB-DIM-Reader) de Christopher Milne-O'Grady (MIT), para leer las DIM cards.
- Formato de las DIM y del combate contrastado con DIM-Modifier y VitalWear.
- Bono de trofeos basado en el mod *Digimon Link Pendulum*.

## Aviso legal

*Digimon* y *Vital Bracelet* son marcas registradas de Bandai; aquí se mencionan
solo para indicar con qué dispositivo funciona el programa. Este proyecto no
está afiliado, patrocinado ni respaldado por Bandai. Los sprites de cada Digimon
se leen de los archivos del propio usuario; el programa no los incluye. Si
Bandai pide retirar algún material, se retirará.
