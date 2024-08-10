# `mousegen` (working title)

_(I may factor this into it's own project/build.)_

Alternative utilities for generating
[Bibata](https://github.com/ful1e5/Bibata_Cursor) (and related families) mouse
cursors.  It all started from:

-   [ful1e5/Bibata_Cursor#149](https://github.com/ful1e5/Bibata_Cursor/issues/149):
    Multi-resolution cursors on Windows

but has been expanded with number of other tweaks I've found improving the
final result.  One major difference with the original Bibata (clickgen) build
is this tool needs the pointer hotspots defined into the individual SVG
sources like:

```xml
  <circle id="cursor-hotspot" cx="..." cy="..." ... display="none"/>
```

The `display="none"` ensures this shape doesn't appear in the result bitmap.
(**REVISIT:** I could make this automatic during generation, but for preview
purposes it is better explicitly specified.)

Having the hotspots into the individual SVG files allows for better
maintenance and control.  For example, the current Bibata "Modern" and
"Original" variants share hotspots for same named cursors.  This may not be
always possible (because of significantly differing shapes), and hotspots
defined elsewhere could more easily become out of sync with the SVG sources.

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

    Details: [SVGCursorMetadata](src/main/java/io/github/stanio/mousegen/svg/SVGCursorMetadata.java),
    [AnchorPoint](src/main/java/io/github/stanio/mousegen/svg/AnchorPoint.java).

## Build

    gradlew :bibata:shadowJar

Copy `build/libs/bibata.jar` to the `Bibata_Cursor` working copy.

## Usage

    > java -jar bibata.jar --help
    USAGE: bibata <command> [<args>]

### `svgsize` (deprecated)

Utility for adjusting SVG sources' `width`, `height`, and `viewBox`.  Prepares the
SVG sources for `yarn render`:

    > java -jar bibata.jar svgsize --help
    USAGE: svgsize <target-size> <viewbox-size> <svg-dir>

_Note:_ This functionality is automatically done by [`render`](#render).

### `wincur` (deprectated, for removal)

Utility creating Bibata Windows cursors from pre-rendered bitmaps (`yarn render`):

    > java -jar bibata.jar wincur --help
    USAGE: wincur [--all-cursors] <bitmaps-dir>

### `render`

Utility for rendering Bibata cursor bitmap images.  Alternative to cbmp's
`yarn render` / [Puppeteer](https://pptr.dev/), using the
[JSVG](https://github.com/weisJ/jsvg) (Java SVG renderer) library (or
optionally [Batik](https://xmlgraphics.apache.org/batik/)).  Can create
Windows and X (Linux) cursors directly (a la clickgen), not saving intermediate
bitmaps:

    > java -jar bibata.jar render --help
    USAGE: render [<project-path>] [{--windows-cursors|--linux-cursors}]

## Similar Tools

Some references I've stumbled upon:

-   [ful1e5/cbmp](https://github.com/ful1e5/cbmp),
    [ful1e5/clickgen](https://github.com/ful1e5/clickgen)
-   [charakterziffer/cursor-toolbox](https://github.com/charakterziffer/cursor-toolbox)
-   [mxre/cursor](https://github.com/mxre/cursor)
-   [quantum5/win2xcur](https://github.com/quantum5/win2xcur) (and x2wincur)
-   [xcursorgen](https://gitlab.freedesktop.org/xorg/app/xcursorgen)
    (on [ArchWiki](https://wiki.archlinux.org/title/Xcursorgen))
