Alternative utilities for generating
[Bibata](https://github.com/ful1e5/Bibata_Cursor) bitmap images and Windows
cursors.

See also:
[Multi-resolution cursors on Windows](https://github.com/ful1e5/Bibata_Cursor/issues/149)

## Build

    gradlew :bibata:shadowJar

Copy `build/libs/bibata.jar` to the `Bibata_Cursor` working copy.

## Usage

    > java -jar bibata.jar --help
    USAGE: bibata <command> [<args>]

### `svgsize`

Utility for adjusting SVG sources' `width`, `height`, and `viewBox`.  Prepares the
SVG sources for `yarn render`:

    > java -jar bibata.jar svgsize --help
    USAGE: svgsize <target-size> <viewbox-size> <svg-dir>

### `wincur`

Utility creating Bibata Windows cursors from pre-rendered bitmaps (`yarn render`):

    > java -jar bibata.jar wincur --help
    USAGE: wincur [--all-cursors] <bitmaps-dir>

### `render`

Utility for rendering Bibata cursor bitmap images.  Alternative to `yarn render` /
[Puppeteer](https://pptr.dev/), using the
[Batik SVG Toolkit](https://xmlgraphics.apache.org/batik/).  Can create Windows
cursors directly, not saving intermediate bitmaps:

    > java -jar bibata.jar render --help
    USAGE: render [<base-path>] [--standard-sizes] [--windows-cursors]
