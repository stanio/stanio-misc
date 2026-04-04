# `mousegen` –  a swiss army knife for mouse cursors

Provides tools for generating and processing mouse cursors (and cursor themes)
in number of different formats:

-   Windows cursors
-   Xcursors
-   [Mousecape](https://github.com/alexzielenski/Mousecape) (macOS)
-   [KDE scalable cursors](https://blog.vladzahorodnii.com/2024/10/06/svg-cursors-everything-that-you-need-to-know-about-them/)
-   [Hyprcursors](https://standards.hyprland.org/hyprcursor/) (SVG)

The main tool [`render`](#render) generates mouse cursors from SVG sources.

-----

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
share hotspot configuration for same-named cursors.  This may not always be
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

    gradlew :mousegen:assemble

`mousegen/build/libs/` contains executable JARs with and without dependencies included.

`mousegen/build/distributions/` contains
[self-executable JAR](https://skife.org/java/unix/2011/06/20/really_executable_jars.html)
scripts (require `java` on the `PATH`):

-   [`mousegen.sh`](src/scripts/mousegen.sh) (Posix)
-   [`mousegen.cmd`](src/scripts/mousegen.cmd) (Windows)

If you have `stanio-misc` and [`Bibata_Cursor`](https://github.com/stanio/Bibata_Cursor)
checked out next to each other, you could use:

    gradlew installExecutable -PinstallDir=../../Bibata_Cursor/bin

to get a fresh `mousegen` executable for the Bibata_Cursor (stanio) build.

## Usage

    > java -jar mousegen.jar --help
    USAGE: mousegen <command> [<args>]

### `dump`

Dumps (decompiles) one or more supported-format cursor files (Windows, XCursor,
Mousecape) to individual bitmaps and a `*.cursor` (Xcursorgen) config file with
the relevant metadata (nominal size, hotspot, bitmap file, animation duration).

    > mousegen dump [-d <output-dir>] <cursor-file>...

### `compile`

Creates one or more cursor files in a specified output format from specified
(`xcursorgen`) config-file(s).

    > mousegen compile --help
    USAGE: compile {--windows-cursors|--linux-cursors|--mousecape-theme} [-d <output>] <input>...

Using `--generate-sizes`, could be used to create final package from a
source with just one "master" (large enough) bitmap size:

    # pointer.cursor
    256 50 50 pointer.png

    > mousegen compile pointer.cursor -r 32,48,64,96 --generate-sizes ...

Otherwise, the `-r` option(s) will filter the available sizes and possibly
produce an empty result.

In combination with the `dump` command, could be used to convert cursors from one
format to another.  Note, however, different formats have specific requirements on
the exact output resolutions that are not handled automagically.

The following are some of my observations.  Linux nominal size 24 roughly maps to
Windows base size 32.  When converting Windows to Linux, you would need to specify:

    mousegen compile -s 1/0.75 ...

When converting Linux to Windows:

    mousegen compile -s 1/1.333 ...

_Windows sizes_

Display scale →<br>Pointer size ↓ | 100% | 125% | 150% | 175% | 200% | 250%
  --: |  --: |  --: |  --: |  --: |  --: |  --:
**1** |   32 |    ← |   48 |    → |   64 |    ←
**2** |   48 |    ← |   72 |    → |   96 |    ←
**3** |   64 |    ← |   96 |    → |  128 |    ←
**4** |   80 |    ← |  120 |    → |  160 |    ←
**5** |   96 |    ← |  144 |    → |  192 |    ←

_Linux sizes_

Note that these are nominal (logical) sizes which may not match the exact bitmap
sizes.  Ubuntu 22/Gnome:

Display scale →<br>Pointer size ↓ | 100% | 125% | 150% | 175% | 200% | 225% | 300%
  --:           |  --: |  :-: |  :-: |  :-: |  --: |  :-: |  --:
**(Default) 1** |   24 |   →  |   →  |   →  |   48 |   →  |   72
**(Medium) 2**  |   32 |   →  |   →  |   →  |   64 |   →  |   96
**(Large) 3**   |   48 |   →  |   →  |   →  |   96 |   →  |  144
**(Larger) 4**  |   64 |   →  |   →  |   →  |  128 |   →  |  192
**(Largest) 5** |   96 |   →  |   →  |   →  |  192 |   →  |  288

_Mousecape sizes_

As far as I can tell, the exact base sizes don't matter as long they follow:

-   At most 4 sizes/resolutions
-   ×1 (Base/SD), ×2 (HD), ×5 (SD-shake-to-find), ×10 (HD-shake-to-find)

That is, the first (base) size could be any, as long as the following are
derived from it with the given factors.  For example: 32, 64, 160, 320; or
24, 48, 120, 240.  You can omit the ×10 variant to save disk space.

### `render`

Utility for rendering Bibata cursor bitmap images.  Alternative to `cbmp`
(Bibata `yarn render`) / [Puppeteer](https://pptr.dev/), using the
[JSVG](https://github.com/weisJ/jsvg) (Java SVG renderer) library (or
optionally [Batik](https://xmlgraphics.apache.org/batik/)).  Can create
Windows and X (Linux) cursors directly (a la `clickgen`), not saving
intermediate bitmaps:

    > java -jar mousegen.jar render --help
    USAGE: render [<project-path>] [{--windows-cursors|--linux-cursors|--mousecape-theme|--scalable-cursors}]

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

### `hyprcursor`

[`render`](#render) can generate
[KDE SVG cursors](https://blog.vladzahorodnii.com/2024/10/06/svg-cursors-everything-that-you-need-to-know-about-them/):

    mousegen render --scalable-cursors ...

This utility can convert these into
[hyprcursor](https://standards.hyprland.org/hyprcursor/) format:

    mousegen hyprcursor ...

The SVGs are reused "as is".  The metadata is converted, and individual cursors
packaged as required.

## Similar Tools

Some references I've stumbled upon:

-   [ful1e5/cbmp](https://github.com/ful1e5/cbmp),
    [ful1e5/clickgen](https://github.com/ful1e5/clickgen)
-   [charakterziffer/cursor-toolbox](https://github.com/charakterziffer/cursor-toolbox)
-   [mxre/cursor](https://github.com/mxre/cursor)
-   [quantum5/win2xcur](https://github.com/quantum5/win2xcur) (and x2wincur)
-   [xcursorgen](https://www.x.org/releases/current/doc/man/man1/xcursorgen.1.xhtml)
    (on [ArchWiki](https://wiki.archlinux.org/title/Xcursorgen))
-   [CursorCreate](https://github.com/isaacrobinson2000/CursorCreate)
-   [kcursorgen](https://blogs.kde.org/2025/01/12/kcursorgen-and-svg-cursors/)
