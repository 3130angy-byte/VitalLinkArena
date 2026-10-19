# Material de terceros / Third-party notices

La licencia MIT de este repositorio (`LICENSE`) cubre solo el trabajo
original del proyecto. Todo lo de esta lista pertenece a sus dueños y
**no** queda bajo esa licencia.

## Bandai — marcas y sprites

- **Digimon** y **Vital Bracelet** son marcas registradas de Bandai. El
  nombre del proyecto las usa solo para describir con qué dispositivo
  funciona. Este es un **proyecto de fans sin fines de lucro, no afiliado,
  patrocinado ni respaldado por Bandai**.
- Los sprites de ataque de `src/main/resources/attacks/` (`atk_s_*.png`,
  `atk_l_*.png` y la carpeta `Raid Boss Attacks/`) se extrajeron del
  firmware del Vital Bracelet y son **© Bandai**. Se incluyen solo para que
  el programa muestre los ataques; no se otorga ningún permiso sobre ellos.
  La excepción es `impact.png`, que es arte original del proyecto.
- Los sprites, nombres y stats de cada Digimon **no vienen en el proyecto**:
  el programa los lee de las VS DIM y DIM cards que cada usuario tiene.

Si Bandai pide retirar algún material, se retirará.

## Librerías

### VB-DIM-Reader (incluida en `libs/`)

https://github.com/cfogrady/VB-DIM-Reader

```
MIT License

Copyright (c) 2022 Christopher Milne-O'Grady

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Descargadas por Gradle y copiadas en el paquete descargable

| Librería | Licencia |
|---|---|
| OpenJFX 21 (JavaFX) | GPL v2 con Classpath Exception |
| JSON-java (`org.json:json`) | Dominio público |
| Apache Commons IO | Apache License 2.0 |
| SLF4J API | MIT |
| `at.favre.lib:bytes` | Apache License 2.0 |
| Gradle Wrapper (`gradle/wrapper/`) | Apache License 2.0 |

El Java incluido en el descargable (Eclipse Temurin / OpenJDK 17) es GPL v2
con Classpath Exception; sus avisos completos vienen en
`runtime/legal/` dentro del paquete.
