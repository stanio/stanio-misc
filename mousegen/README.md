# `mousegen`

_Tool for generating Windows and X11 mouse cursors from SVG sources._

Alternative utilities for generating
[Bibata](https://github.com/ful1e5/Bibata_Cursor) (and related families) mouse
cursors.  It all started from:

-   [ful1e5/Bibata_Cursor#149](https://github.com/ful1e5/Bibata_Cursor/issues/149):
    Multi-resolution cursors on Windows

but has been expanded with number of other tweaks I've found improving the
final result.  One major difference from the original Bibata (`clickgen`) build
is this tool needs the pointer hotspots defined into the individual SVG sources
like:

```xml
  <circle id="cursor-hotspot" cx="..." cy="..." ... display="none"/>
```

The `display="none"` ensures this shape doesn't appear in the result bitmap,
while visibility could be easily toggled in authoring tools like Inkscape.

Having the hotspots into the individual SVG files allows for better maintenance
and control.  For example, the current Bibata "Modern" and "Original" variants
share hotspot configuration for same named cursors.  This may not be always
feasible because of significantly differing shapes, and hotspots defined
separately could more easily become out of sync with the SVG sources.

For this, and all other purposes, I'm maintaining my own fork:
[stanio/Bibata_Cursor](https://github.com/stanio/Bibata_Cursor).

## Enhancements

Compared to the original Bibata, these improve on (achieve best possible)
clarity/sharpness:

-   Render each target resolution from SVG, rather than resampling a single
    "master" bitmap; and further
-   `align-anchor` (if present) in SVG source is used to align bitmap result to
    each target pixel grid

    Demo in comment to [stanio/Bibata_Cursor@`8cc992f`](https://github.com/stanio/Bibata_Cursor/commit/8cc992faefc8d9327957d0d7a58b0ac1687bcc5f#commitcomment-131173743).

    Details: [SVGCursorMetadata](src/main/java/io/github/stanio/mousegen/svg/SVGCursorMetadata.java).

## Build

    gradlew :mousegen:shadowJar

Copy `build/libs/mousegen.jar` to the `Bibata_Cursor` working copy.

## Usage

    > java -jar mousegen.jar --help
    USAGE: mousegen <command> [<args>]

### `svgsize` (deprecated)

Utility for adjusting SVG sources' `width`, `height`, and `viewBox` for
rendering at a single target resolution/size.  Previously used as a
pre-processor to `cbmp` (Bibata `yarn render`) followed by `wincur`.

    > java -jar mousegen.jar svgsize --help
    USAGE: svgsize <target-size> <viewbox-size> <svg-dir>

_Note:_ This functionality is automatically done by [`render`](#render).

### `wincur` (experimental)

Utility creating Bibata Windows cursors from pre-rendered bitmaps a la
`xcursorgen`:

    > java -jar mousegen.jar wincur --help
    USAGE: wincur [--all-cursors] <bitmaps-dir>

> [!NOTE]
> This will be eventually replaced by a `compile` command using `xcursorgen`
> config files but providing options for differernt output formats as supported
> by mousegen.

### `dump`

Dumps (decompiles) one or more supported-format cursor files (Windows, XCursor,
Mousecape) to individual bitmaps and a `*.cursor` (Xcursorgen) config file with
the relevant metadata (nominal size, hotspot, bitmap file, animation duration).

    > java -jar mousegen.jar dump [-d <output-dir>] <cursor-file>...

### `render`

Utility for rendering Bibata cursor bitmap images.  Alternative to `cbmp`
(Bibata `yarn render`) / [Puppeteer](https://pptr.dev/), using the
[JSVG](https://github.com/weisJ/jsvg) (Java SVG renderer) library (or
optionally [Batik](https://xmlgraphics.apache.org/batik/)).  Can create
Windows and X (Linux) cursors directly (a la `clickgen`), not saving
intermediate bitmaps:

    > java -jar mousegen.jar render --help
    USAGE: render [<project-path>] [{--windows-cursors|--linux-cursors}]

See some more examples on the
[Wiki page](https://github.com/stanio/stanio-misc/wiki/mousegen).

### `linuxThemeFiles`

Generates `index.theme` and `cursor.theme` files.

-   https://specifications.freedesktop.org/icon-theme-spec/

### `windowsInstallScripts`

Generates `install.inf` installation scripts.

-   https://learn.microsoft.com/en-us/windows-hardware/drivers/install/overview-of-inf-files

For manual setup on Windows see:

-   [ful1e5/Bibata_Cursor#137](https://github.com/ful1e5/Bibata_Cursor/issues/137#issuecomment-1731713946):
    Install under Windows user account doesn't work

### `x11Symlinks`

Creates Xcursor symlinks.  Specialized alternative to native tools like
`ln` (Linux) and `mklink` (Windows).

## Similar Tools

Some references I've stumbled upon:

-   [ful1e5/cbmp](https://github.com/ful1e5/cbmp),
    [ful1e5/clickgen](https://github.com/ful1e5/clickgen)
-   [charakterziffer/cursor-toolbox](https://github.com/charakterziffer/cursor-toolbox)
-   [mxre/cursor](https://github.com/mxre/cursor)
-   [quantum5/win2xcur](https://github.com/quantum5/win2xcur) (and x2wincur)
-   [xcursorgen](https://gitlab.freedesktop.org/xorg/app/xcursorgen)
    (on [ArchWiki](https://wiki.archlinux.org/title/Xcursorgen))
-   [CursorCreate](https://github.com/isaacrobinson2000/CursorCreate)
-   [Mousecape](https://github.com/alexzielenski/Mousecape) (macOS)
