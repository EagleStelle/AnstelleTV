---
name: AnstelleTV
description: A personal media library played as broadcast TV, dressed as the set's own on-screen display.
colors:
  ink: "#130C0F"
  coal: "#20161A"
  coal-high: "#291E23"
  line: "#34272C"
  bone: "#F9F1F4"
  muted: "#BFABB3"
  rose: "#F978B6"
  deep-rose: "#4F1A35"
  blush: "#FFCEE2"
  aired: "#181214"
  danger: "#D32F2F"
typography:
  display-large:
    fontFamily: "Barlow Condensed"
    fontSize: "64px"
    fontWeight: 700
    lineHeight: "68px"
    letterSpacing: "-0.01em"
    fontFeature: "tnum"
  display-medium:
    fontFamily: "Barlow Condensed"
    fontSize: "48px"
    fontWeight: 700
    lineHeight: "52px"
    letterSpacing: "-0.01em"
    fontFeature: "tnum"
  display-small:
    fontFamily: "Barlow Condensed"
    fontSize: "36px"
    fontWeight: 600
    lineHeight: "40px"
    fontFeature: "tnum"
  headline-large:
    fontFamily: "Barlow Condensed"
    fontSize: "34px"
    fontWeight: 600
    lineHeight: "38px"
    fontFeature: "tnum"
  headline-medium:
    fontFamily: "Barlow Condensed"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: "34px"
    fontFeature: "tnum"
  headline-small:
    fontFamily: "Barlow Condensed"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: "28px"
    fontFeature: "tnum"
  title-large:
    fontFamily: "Barlow"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "26px"
  title-medium:
    fontFamily: "Barlow"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "22px"
  title-small:
    fontFamily: "Barlow"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: "20px"
  body-large:
    fontFamily: "Barlow"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
  body-medium:
    fontFamily: "Barlow"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
  body-small:
    fontFamily: "Barlow"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "16px"
  label-large:
    fontFamily: "Barlow"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "18px"
    letterSpacing: "0.01em"
  label-medium:
    fontFamily: "Barlow Condensed"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: "16px"
    letterSpacing: "0.06em"
    fontFeature: "tnum"
  label-small:
    fontFamily: "Barlow Condensed"
    fontSize: "11px"
    fontWeight: 600
    lineHeight: "14px"
    letterSpacing: "0.06em"
    fontFeature: "tnum"
rounded:
  control: "2px"
  panel: "4px"
spacing:
  page-phone: "16px"
  page-tv: "48px"
  browse-side-phone: "12px"
  browse-side-tv: "32px"
  browse-gap-phone: "6px"
  browse-gap-tv: "8px"
  row-gap: "6px"
  panel-phone: "18px"
  panel-tv: "24px"
  rail-tablet: "88px"
  rail-tv: "80px"
  bar: "64px"
components:
  focus-surface:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    rounded: "{rounded.control}"
  focus-surface-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  focus-surface-pressed:
    backgroundColor: "{colors.coal-high}"
    textColor: "{colors.bone}"
  button-primary-touch:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
    typography: "{typography.label-large}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "48px"
  button-primary-touch-focused:
    backgroundColor: "{colors.bone}"
    textColor: "{colors.ink}"
  button-tv:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.label-large}"
    rounded: "{rounded.control}"
    padding: "0 24px"
    height: "52px"
  button-secondary:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.label-large}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "48px"
  button-settings:
    backgroundColor: "{colors.coal-high}"
    textColor: "{colors.bone}"
    rounded: "{rounded.control}"
  button-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.bone}"
    rounded: "{rounded.control}"
  button-web:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    rounded: "{rounded.control}"
    height: "40px"
  settings-row:
    textColor: "{colors.bone}"
    typography: "{typography.body-large}"
    padding: "10px 16px"
    height: "56px"
  settings-row-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  menu-row:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.title-medium}"
    rounded: "{rounded.control}"
    padding: "10px 16px"
    height: "56px"
  menu-row-tv:
    padding: "12px 20px"
    height: "64px"
  number-block:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 10px"
  osd-panel:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    rounded: "{rounded.panel}"
    padding: "{spacing.panel-phone}"
  osd-badge:
    textColor: "{colors.bone}"
    typography: "{typography.label-small}"
    rounded: "{rounded.control}"
    padding: "1px 5px"
  guide-cell:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    rounded: "{rounded.control}"
    padding: "4px 8px"
  guide-cell-aired:
    backgroundColor: "{colors.aired}"
    textColor: "{colors.muted}"
  guide-cell-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  guide-cell-off-air:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.muted}"
  nav-bar:
    backgroundColor: "{colors.coal}"
    height: "{spacing.bar}"
  nav-tab:
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
  nav-tab-selected:
    textColor: "{colors.bone}"
  nav-tab-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  text-field:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.body-large}"
    rounded: "{rounded.control}"
---

# Design System: AnstelleTV

## Overview

**Creative North Star: "On-Screen Display"**

The app speaks the television's own on-screen display: the banner that drops in on a channel change, the now/next bar, the digits punched in on the remote. Every screen is built from those parts, except Settings, which speaks the device's own settings. It reads as the set itself, not a streaming catalog laid over it, and refuses the catalog default of rounded poster rows on navy.

The material is flat and solid. Rose, the default theme, carries the world on three values (ink ground, bone type, rose signal) with measured steps between them. Depth is a step on that ladder, never a shadow. Corners are nearly square. Focus is a fill, never a ring or a zoom: the focused thing turns rose and its text ink, as a set-top box highlights a menu line. The palette comes from a character's outfit (black-and-white contrast with a pink signal); the source is never named.

Density follows the screen: 10-foot menus get larger rows and digits, phones keep the same parts at hand size. On TV the guide and the player's banner are set tighter than menus so the guide shows five or six channels and the picture stays clear. Labels never shrink to fit; a screen shows fewer rows. Motion is short hard snaps (about 140 to 180 ms), no bounce.

**Key Characteristics:**
- Ink, bone and rose, with measured steps of one hue (about 352° in OKLCH) between them.
- Focus is the accent fill with on-accent content, on every control, cell, tab and tile.
- Flat solid fields, hairline edges, near-square corners (2 px controls, 4 px panels).
- Condensed grotesque with tabular figures for numbers, times, titles and field labels.
- State by form as well as color: solid square on air, hollow square up next, ticked square watched.
- One navigation component on every device.

## Colors

Rose is the default identity; every theme fills the same roles, so screens never name a hue.

### Primary
- **Signal Rose** (rose, role `accent`): focus fills, the guide's now line and playhead, number blocks, on-air markers, the open tab's rule, the touch primary action, the mark's tile. Text on it is `onAccent` (ink in Rose).

### Secondary
- **Deep Rose** (deep-rose, `accentContainer`) with **Blush** (blush, `onAccentContainer`): selected-not-focused things, role chips, text selection.

### Neutral
- **Ink** (ink, `background`): the ground, off-air and restricted guide cells.
- **Coal** (coal, `surface`): rows, panels, the nav bar and rail, text fields, on-air guide cells and the player's shades.
- **Coal High** (coal-high, `surfaceHigh`): pressed rows, Settings buttons, placeholders.
- **Line** (line, `line`): hairlines, panel edges, empty ladder segments, empty entry slots.
- **Bone** (bone, `text`): all primary text, the player's banner included.
- **Muted** (muted, `textMuted`): secondary text, unselected tabs, badge outlines, the unfocused guide selection. Tinted, never neutral grey.
- **Aired** (aired, `guideCellPast`): guide cells that have finished.
- **Guide runs** (`guideRuns`, two or more): upcoming cells of other shows take them in turn, so neighbors read apart. Rose's are dark teal, ochre, slate and moss.

### Named Rules
**The Theme Scope Rule.** Rose is the brand: the mark, launcher art and the web UI before pairing wear it, and its neutrals sit on one hue with no stock Tailwind or shadcn values. Built-in and custom themes may change hue, go light (Paper) or pick another display cut; they must keep the roles, the focus behavior, the geometry and the contrast floors.

**The Role Not Hue Rule.** Code names roles, never hues: background, surface, surfaceHigh, line, text, textMuted, accent, onAccent, accentContainer, onAccentContainer, guideCellPast and guideRuns. A role that would always equal another doesn't exist; on-air cells and the player's shades use surface, banner text uses text, the now line uses accent.

**The Rose Means Here Rule.** The accent marks where you are and what is live: focus, now, on air, the one touch primary. Body copy and decoration never use it.

**The Danger Rule** (user decision). One red stands outside every theme: #D32F2F with white (`DangerColor`, `OnDangerColor`), for what destroys or hands over (delete, remove, transfer). A destructive button is red at rest on every device and turns white with red ink on focus; a destructive row or menu entry carries red on its icon and fills red on focus.

**The Readable Floor Rule.** Every theme meets fixed floors, checked by `ThemeContrast` and `ThemeContrastTest`: text on background and surface 7:1, muted text 4.5:1, on-accent on accent 4.5:1, accent on background 3:1, on-container on container 4.5:1, text on each guide run 7:1. The designer names a miss in words (which color to brighten or darken), never as a ratio.

## Typography

**Display Font:** Barlow Condensed (bundled static Medium, SemiBold, Bold)
**Body Font:** Barlow (bundled static Regular, Medium, SemiBold, Bold)

**Character:** One grotesque family in two widths. The condensed cut is the OSD's voice (channel numbers, clock times, titles, field labels) with tabular figures; the regular width carries titles, body and button labels. Themes may swap either role for Sans, Condensed, Serif or Mono; Serif and Mono use the system faces.

### Hierarchy
- **Display** (Bold/SemiBold, 64/48/36 px): channel numbers in the banner and number entry, the full-screen stand-in mark, placeholder letters.
- **Headline** (SemiBold, 34/30/24 px): page titles, the banner's NOW title, panel titles, the wordmark.
- **Title** (SemiBold/Medium, 20/16/14 px): menu rows, tabs, the NEXT title, tile names.
- **Body** (Regular, 16/14/12 px): descriptions, row summaries, program detail. Page notes stop at 720 px wide.
- **Label** (SemiBold): label-large (regular width) for buttons and row values; label-medium and label-small (condensed, tracked 0.06 em, uppercase) for OSD field labels, section headings and badges.

### Named Rules
**The One Scale Rule.** Every size is multiplied once by the theme's text size (90% to 130%) through `ResolvedTheme.sp`, which also builds the roles above. Local sizes (guide cells and channel column, the time header, nav labels, `browseHeading`) are named variants that keep their base sp and inherit the scale. Type stays in sp, so the system font setting applies too.

**The Tabular Rule.** Any number that changes in place (channel number, time, count, progress) sets in the condensed cut with tabular figures.

**The Bundled Rule.** Fonts ship as static files. No downloadable fonts, no variable axes; the oldest supported TVs can't use either.

## Layout

Sizes are dp (type sp), written as px. Pages pad 48 px at the sides on TV, 16 on phones. Menus stack rows 6 px apart. Rows are at least 64 px on TV and 56 on touch; buttons 52 and 48.

- **Shell:** one breakpoint at 600 px. Phones get a 64 px bottom bar; tablets an 88 px rail, TV an 80 px rail with phone-sized tabs (24 px icon, 11 px label), headed by the mark (36 px tablet, 32 TV).
- **Settings** (user decision): on TV and touch screens 840 px and wider, Google TV's guided panel: guidance on the ground (42%: muted breadcrumb, display-small title, the note behind an info mark, then a preview or readout), the list on a coal panel full height, rows 2 px apart. Phones get Android's settings: a 64 px bar with a collapsing headline-medium title (the bar itself on the home), rows edge to edge, the note closing the list.
- **Guide:** fixed layout, restyle only: preview top-left meeting the details with no gap (on an upright phone the preview spans the width, details under it), tabs, then the grid. Details sit on the show's backdrop under the wash, title and channel logos sharing a 56 dp height on TV and 40 dp on phones, with fixed overview lines (three TV, four phone) so the grid never moves. Guide rows have three densities shared by touch and TV: Compact 48 px, Comfortable 56 px and Spacious 64 px, in equal 8 px steps. On TV the channel column is 148 px (172 once any channel has a logo; 132 on phones) and an hour spans 300 px.
- **Player:** full screen, no shell; phones turn sideways and hide system bars. Nothing sits on a panel (user decision). Text keeps title-safe margins (48 by 24 px TV, 24 px sides on phones). Subtitles step above the banner while it shows. Phones size the banner for the hand, not a copy of the TV's (user decision): TV-sized type or smaller, no more height than touch targets need.
- **On demand and the dashboard** keep the guide's margins so screens line up: 32/12/8 px (sides, top and bottom, band gap) on TV, 12/8/6 on phones. The banner leads (3 px focus frame on TV, edge to edge on phones), then one row per guide group under a `browseHeading` (15 px TV, 13 phone, 18 line). Tile pictures, the banner and headings share the side margin.
- **Detail pages** open on an edge-to-edge hero (backdrop, number block and name, still or poster with its ladder, logo or title, facts, overview; on phones the backdrop takes the banners' 80% wash, since the text lies over it), then one row of watch keys (a started program's ladder and time left above them, the sheet's 8 px clear on both sides; no watched mark, the watched key says it), then season tabs and episodes on the plain ground. On TV the whole page fits one screen: the artwork is always one size, and the hero ends early enough for the tabs and a full row of episode cards, the artwork's faded foot running on under them. Episodes are the same rail as the on-demand player's (see *Episode strip*). TV catalog banners and hero backdrops share one height: five sixths of 16:9. Mobile catalog banners are 320 dp, growing with system font scale above 1.0.
- **Web UI:** the TV's world in a browser, wearing the paired profile's theme (Rose before pairing): colors, fonts, text size and scanlines, as Tailwind utilities over the theme roles. A website's layout, not the guided panel (user decision): a coal top bar (mark, then areas with icon and label; icons alone under 420 px) over a 1152 px container with 16 px gutters (24 from 840 px). Page titles are headline-small, headline-large from 840 px, with the page's verbs at their end; the note sits behind an info mark, at most 68 characters wide. Lists lie on the ground, rows bleeding 16 px into the gutter. Settings pages keep a 768 px column; builders (a lineup's reach, a theme's preview) put the result in a sticky 360 px column from 1024 px. Buttons are at least 40 px; rows 44 px, 56 with a summary. Hover fills exactly as focus does; keyboard focus is the same fill, never the browser ring.

## Elevation & Depth

Flat by default: no shadows, glows or Material tonal tint, and no blur except an age-restricted title and logo (user decision). Depth is a step up the neutral ladder plus, over video, a 1 px line edge. Two flat translucent layers exist outside the player: the past shade (20% dark over aired hours and watched stills) and the backdrop wash (the ground at 80% over artwork). Over live video the players use shades, the one gradient exception.

### Named Rules
**The Shade Over Picture Rule** (user decision). The OSD sits on the picture, never on a panel; the surface tone fades in only where text needs it. The edge shade rises from the bottom, gone 96 px above the banner on TV (48 on phones), 70% at the first line and 88% at the foot. The corner shade is a quarter ellipse behind the top-end ident, 1.25 times its diagonal, 88% in the corner. A message sits on a soft elliptical shade; a wait (the segment ring) takes none. These are the only gradients and they take no edge, corner or fill.

**The Wash Not Fade Rule.** Artwork under text takes one flat 80% wash of the ground, never a fade or blur: bone stays at 9:1 and muted above 4.5:1 even over a white sky.

**The Step Not Shadow Rule.** What reads as above something moves one step up the ladder and, over video, takes a line hairline. It never casts a shadow.

**The No Zoom Rule.** Focus never scales, glows or lifts. Compose for TV's focus scale and glow are off. The shell icon's brief press shrink is the one motion on a tab.

**The Fill Not Frame Rule** (user decision). A button or key is a fill, never a frame: no outline at rest, focus or press. Hairlines belong to panels over video, empty entry slots, badges and guide cells.

## Shapes

2 px corners on controls, rows, guide cells, posters, buttons, badges and number blocks; 4 px on panels over video and dialogs; nothing rounder except the platform Switch, RadioButton and Checkbox. Edges are 1 px line (panels, off-air cells, entry slots) or muted (badges, the unfocused guide selection).

Markers are squares: on air a solid 8 px square, up next the same hollow (1.5 px stroke), watched a 10 px bone square with a tick cut in ink, the now line's head an 8 px square on a 2 px stem. The current episode's ring round its still is a marker, not a frame on a key, so the Fill Not Frame Rule doesn't touch it.

Progress is `SegmentLadder`: 6 px high, fixed-width square blocks, a 3 px gap, accent over line. The theme's cut frequency (4 to 64 cuts per 320 px, default 20) sets block width everywhere; longer bars hold more blocks, centered, never stretched or clipped. The indeterminate wait is `SegmentRing`: 16 segments in a 48 px ring, 6 px deep, a rose run stepping clockwise in Material's indeterminate rhythm, on the bare picture with no label (screen readers hear Tuning or Loading).

The mark is a channel bug: an ink "A" on a rose tile (corner 6 on a 108 grid), its crossbar running past the right leg like the now line out of frame. The in-app mark, launcher icon and TV banner share the geometry.

## Components

### Buttons
Flat, solid, labelled: the OSD's action keys.
- **Touch primary:** rose with ink label, 48 px tall, 20 px side padding, optional 20 px icon; one per screen. Focus turns it bone.
- **TV:** no button is rose at rest (user decision: rose only means focus there). The primary takes coal at 52 px with 24 px padding; focus leads to it.
- **Secondary:** coal with bone label; in Settings and dialogs coal high on every device. Focus fills any button rose with ink.
- **In Settings** (user decision): buttons share their line evenly, label centered; one alone spans the list; labels that won't fit stack 8 px apart.
- **Keep buttons** (user decision): a page whose edits wait for Save (Playback, the theme designer) and every pane it opens pin one line of buttons across the list's top that never scrolls, sharing it evenly as Settings' buttons do: Save and a red Discard on the page (the theme designer adds Use theme), Done and a red Discard on a pane, the keep verb the primary, both on only while there are edits (`KeepButtons`). Phones put the line under the bar, TV and wide screens atop the list's panel, one Up from its first row. Back on a pane drops its edits; Back on the page with edits unsaved asks *Save changes?* with Save and a red Discard, so Save is one key away however far the list is scrolled.
- **Watch key:** a detail page's ways to watch plus Add to a channel, on a row of their own: equal coal rectangles exactly as high as the player's banner keys (one shared `osdKeyHeight`: 48 dp, grown with system font and theme type scale), 8 dp apart, flush left, label under the key. The row is cut into five slots (fewer only when a slot would drop under 64 dp), and three or four keys keep the five-slot width. Icon on the key, uppercase label-medium under it. Focus fills the key rose and turns the label rose; on touch the first key is the rose primary. Title and episode pages add a watched toggle; on a channel page an episode offers Go to Series and only a movie offers Open on Demand. Keys past the slots fold into More.
- **Phone dialogs** keep Android's text actions (the answer in rose, Cancel in bone); everywhere else actions are filled keys on the focus surface.

### Cards / Containers
- **OSD panel:** coal, 1 px line edge, 4 px corners, 24 px padding TV, 18 touch. Used for the action dialog only.
- **Action dialog:** an OSD panel up to 560 px over the 80% wash: number block and title, a status line, then one menu row per choice. Focus starts on the first row; Back or a tap outside closes it.
- **Channel tile:** a transparent card with a 3 px inset: the 16:9 backdrop under the wash with the show's logo (40 px TV, 32 phone) and the airing's ladder in its foot strip, then the number block, the name in title-small and what airs in body-small (episode number muted, two spaces, title in bone). Focus fills the whole card rose; the number block inverts. Off air or restricted, the picture is the ground with the line stand-in mark.
- **Status line:** marker, the field label in the accent (muted once aired), then times in muted, all label-medium uppercase. Heads banners, heroes, dialogs and the on-air episode row.
- **Episode row:** coal row, 16:9 still (176 px TV, 124 phone) with its watch strip, the episode in title-medium, length or time left, two muted overview lines.
- **Pager:** an 8 px square per slide, 5 px apart, solid for the one showing, hollow muted for the rest.

### Inputs / Fields
- **Text field:** Material 3 outlined field on coal, 1 px line outline, 2 px corners, body-large. Focus turns the outline, label and cursor rose; selection sits on deep rose. On TV, OK opens the keyboard. It is the one control whose focus is an outline.
- **Search slab** (user decisions): On demand's search is a flat coal slab at the tab row's end, a tab's exact height and type, muted at rest, with the open tab's 2 px rose rule at its foot while open. It widens toward the tabs in 160 ms (280 px closed on TV, 200 touch; 560 open). TV focus fills it rose.

### Navigation
One component on every device, styled like YouTube's or Spotify's tabs: icon over a short label on coal, no divider, pill, hover or ripple. The open tab shows the filled icon and a bone SemiBold label; the rest outlined icons and muted Medium labels. Phones get a bottom bar, tablets and TV a rail. On TV focus fills the tab rose. Destinations: Live TV, On demand, Dashboard (while on), Settings, Profiles.

Inside a screen, `OsdTabBar` sets flat condensed tabs with an optional count as a coal `OsdChip` after the label (inverted on the focus fill), never run on as the label's next word; the open one carries the 2 px `OsdTabIndicator` exactly its label's width. Season tabs reuse it.

### Settings Rows
Every control says what it does by what sits at its end (user decision). A row lies flat with no rest fill: a muted 24 px icon (or radio, checkbox, number, swatch), the title in body-large Medium, the state in muted body-medium, the end mark. Focus fills rose with ink and 76% ink for the state. A row may carry an `OsdChip` after its title (DEFAULT on the default theme): label-small on the selected colors, ink with accent letters on focus. At least 56 px, 72 on phones with two lines.
- **Chevron** opens a page. **Switch** flips (platform Material 3 Switch on TV too). **Drop-down mark** opens a radio list. **Pencil** opens typing. **Overflow mark** opens the item's menu, a key of its own when the row has an action. **Drag handle** orders a list (touch: lift to coal high; TV: a key that picks up and moves by arrows). **Nothing** marks a fact that takes no focus.
- Verbs are buttons, never rows; a destructive verb sits apart at the foot and asks first (Backup's Restore from file is the one exception, beside Save backup). Section headings are the OSD field label (24 px above, 4 under); the user's own things are *Custom*, above what the app made. Long lists get a search field. Explanations live in the page's info note only.
- **Readouts:** on the TV Settings home the guidance shows the focused area as a readout (hairline frame, status line, headings with their setting in bone, tabular figures, checks), Themes as its preview.
- **Sheets:** on TV, Google TV's next panel (focus starts on Cancel; a destructive answer is red). On phones, Android's dialog on coal with 4 px corners.

### Menu Row
The line of action dialogs and OSD lists outside Settings: icon, title, optional muted description; the value right in label-large. On TV a value steps with left and right. A chosen item shows an accent check; a drill-in a muted chevron. On touch the screen's one main choice is a primary row, rose with ink, bone on focus.

### Channel Banner (signature)
The OSD straight on the picture over the edge shade (user decision). The ident sits in the top-end corner: the channel's logo (56 dp on TV and phones) over the number in rose display over the name in label-medium. The program column holds the NOW line (tech line at its end), the logo (56 dp on TV and phones, maximum width 360 dp) or title in headline, the detail in muted body-medium, the progress row and the keys. NEXT is its own 240 px column (200 touch) behind a hairline, dropped on screens under 720 px. One banner per player (user decision): it snaps in from below in 140 ms on every tune and seek; focus stays on the picture until OK, Info or Menu. The on-demand banner drops the ident and NEXT, says ON DEMAND with the clock range, and holds ← ON DEMAND and EPISODES keys in its top corners on corner shades.
- **Progress row:** time in (bone, tabular), the `SegmentLadder`, full length (muted). On touch a 24 px strip (48 px target) takes taps and drags; the seek lands on release.
- **Tech line:** at the NOW line's end on every device (user decision): server icon and name, a short hairline, DIRECT PLAY or TRANSCODING, then quality, video and audio badges. Too long, it marquees.
- **Keys:** the column's last row under a hairline. 18 px icons in a 48 px base key on every device, growing with text size; flat fill, 2 px corners, accent focus. Track keys show their icon and value (JAPANESE, OFF) left; live actions (Restart) right. On demand centers five equal transport slots on Play/Pause (icons only, named for screen readers) between equal track and Episodes regions; unavailable slots dim to 45% and focus skips them. Pickers and the episode strip replace the row until a choice or Back. No channel keys: phones swipe to surf.
- **Skip key** (user decisions): while a skippable segment plays, SKIP INTRO and the like stays for the whole segment: a banner key while it shows, alone and preselected at the bottom end while it hides.
- **Episode strip:** one rail on detail pages and in the on-demand player (`SeasonTabs`, `EpisodeCard`, `EpisodeActionsDialog`; the player heads it with EPISODES in rose). Every season gets a tab, a lone one included, its count in a chip. Cards like channel tiles: 174 px TV; 168 touch on detail pages, 132 in the player; 8 px apart on TV, 6 on touch; watch strip at the foot; the tiles' caption line, number quiet, title in bone, nothing under it. The current episode (an episode page's own, the one on a channel page's channel, the one playing) is ringed: a 3 px accent ring filling the tile frame round its still, outside the picture, still and with no other mark (user decision: a border, no animation). Focus fills the whole card, caption included, and turns the ring the fill's ink, so the ring never reads as focus. Watched episodes other than the current one take the past shade and a quiet title. Holding one (or Menu) offers the quick ways to play it.

### Number Entry (signature)
Up to four digit slots replace the ident while digits are typed, in rose display; a slot still to come is a 3 px muted bar. A 16-segment ladder drains over two seconds, then the channel tunes.

### Guide Grid
Cells are coal with 2 px corners: the title in SemiBold body (14 px TV, 15 touch), detail muted (11 TV, 12 touch), both at the theme's text size. Focus fills rose with ink; an unfocused selection keeps a 1 px muted outline. Aired cells take the aired color, upcoming cells of other shows the guide runs. Off-air and restricted cells drop to the ground with a line edge and their icon. A cell under 44 px shows only its fill. The on-air program carries a small accent square. The now line is an accent hairline drawn over the cells, continuing a playhead in the header; aired hours sit under the past shade.

### Channel Logo
One standard 5:3 box everywhere, fitted with proportions kept, no plate or frame, silent to screen readers, no crossfade. Heights come from shared `LogoSize` tokens for players and guide details: Standard 56 dp, Compact 40 dp. Players use Standard on every device; guide details use Standard on TV and Compact on phones. Guide-column logos keep their row-density sizes (22 to 36 px TV, 22 to 28 phone).

### OSD Badge
A fact like 4K, HDR10 or 5.1: label-small in bone inside a 1 px muted outline at 60%, never filled.

### Media Artwork and Progress

Detail pages share one picture/readout layout. Artwork keeps a 16:9 box at every detail type, 72% of the available width on TV and wide screens, independent of the readout's height, and shifts left by one `screenEdge` in those layouts. The larger picture provides room for channel facts and complete watch-key labels. One dithered smootherstep fade, fixed to the artwork box, blends it into the theme ground the same way on channel, title and episode pages: the bottom fade runs from half the picture's height to its foot on every device, and wide layouts also fade from 30% of its width to its right edge, under the readout. The title logo sits over the fading half on phones. The right readout ends at the picture's bottom, with equal `screenSide` margins on both sides and equal `screenEdge` padding above and below. Descriptions have no additional horizontal inset. On TV and wide screens the readout is exactly as wide as a full row of five watch keys plus its `screenSide` margins (`watchKeysWidth`), so the keys fill it and none goes behind More for want of room. The readout runs identity, facts, description, then the progress ladder (the airing's clock on a channel page, what was watched on a title or episode) sitting directly on the watch keys with no gap. Here alone the ladder is the bare `WatchProgress` row, without the watch strip's wash or side padding; with nothing to show it takes no room. Descriptions here and on the On demand banners are only as tall as their text (`DescriptionText`, at most four lines, two on phone banners), so what follows sits right under them; on the banners that is the progress ladder. Both stacks anchor to their foot. A long description truncates to the remaining space; full text remains available on selection. The sticky season heading sits below the hero on every device, full width on the ground after the shared `screenGap`, so it never shares the keys' row.

Shared parts own every case; new callers use them rather than wrappers. `Poster` owns cropping, the title-letter fallback, clipping and watched shading. `MediaProgress` owns the fraction and time left (`1 H 4 MIN`, no "left"). `WatchStrip` owns the strip across a picture's foot, one height in every state.
- **In progress:** the ladder in the accent, time left at the strip's end on episode art and tiles; none without saved progress.
- **Watched through** (marked played, never inferred): the watched square then WATCHED in label-small, in accent and on-accent, so it follows the theme; the picture takes the past shade and the title turns muted. Completed items omit time left.
- **Variants:** catalog movie posters show no time left; title-screen posters center it under the bar; channel pages show airing progress with no watched marks; Next up cards show no strip.
- **Title logo:** shared `TitleLogo` owns the treatment on every surface, with a Standard height and 360 dp maximum width by default. Guide details use Compact on phones; catalog and ranking layouts set their own sizes. The show's logo stands in for its title in fixed-height slots, aligned bottom-start; the readable title returns if it is missing, fails or won't read on a light theme, never repeated.

### Stand-in Mark
What stands in for a picture a channel can't show: the ground with one mark for every reason (`StandInMark`). An icon, the reason uppercase in the condensed voice, then UNTIL and the stretch's end, muted. Reasons: OFF AIR (TV-off icon), AGE RESTRICTED or NO ACCESS (lock); never the rating, the limit or where the program comes from (user decision). Parts scale from the title: icon 0.85, gap 0.3 em, UNTIL at half size. Three sizes: full screen (display-medium), the guide preview (headline-small), and a line (label-medium) on tiles, details and channel pages. Restricted titles, logos and episode lines blur in place (a muted bar before API 31); back-to-back restricted programs are one stretch.

### Profiles
- **Tile:** the user's picture on a square (initial in display type without one), the name under it, a lock chip when a PIN is set, OWNER or ADMIN as a deep-rose `OsdChip`. Focus fills it rose.
- **Page:** a settings page titled with the profile: *Profile* (Theme, Authentication, PIN), then *Permissions* (Admin, Parental Rating, Transfer ownership), Delete profile apart at the foot. Row titles are two words at most.
- **Hidden, not locked** (user decision): what a profile can't change isn't shown; nothing is dimmed or locked in its place.

### Dashboard
A two-pane console like the Settings split: ranks on the ground (52%), the meter on coal (48%) behind a hairline, from 720 px; phones stack them. On TV both panes fit whole, nothing scrolls (user decision); the plot gives up height before text or controls.
- **Ranks:** `OsdTabBar` (Channels, Movies, Series, Genres) with the span in muted label-medium. Number one is a backdrop tile with a rose rank block, logo, time and a ladder of its share; two to five are hairline-ruled rows with muted rank figures. Movies and series are named by their logos on every rank (user decision). New rankings cross-fade in 140 ms.
- **Meter:** Today and Daily average as two ladders on one scale (rose, muted), period tabs, step keys, then a hand-drawn bar chart (Live TV rose under On demand muted, a 2 px sliver between), and habits as a two-column readout.

### Theme Designer
Native and web share one layout: Save (the primary, on only while there are edits), Discard and Use theme as its keep buttons, the name, readability problems in words, then three groups. *Colors* lists each role with its swatch (brightness, saturation and hue ladders over OKLCH, or a hex code, with the pairs it must read against). *Type* sets headings, body and text size. *Screen* sets guide rows, progress cuts and scanlines. One preview (banner, a guide row at the theme's row height with every run, rows, buttons, a selected chip) shows every role and setting, edits included before they are saved. It is the designer's only explanation: no info notes, no swatch block. Every color and every Type or Screen setting opens its own page beside it, never a dialog (ladders for a color, a radio list for a setting) on a copy of the theme the preview shows. Done and Discard are its keep buttons, as Save and Discard are the menu's: Done keeps the edits; Discard or Back drops them without asking. The web's page header holds Done and a red Discard. The settings are defined once in `themeChoices` and the web gets them from the device. Leaving the device's designer with edits unsaved asks *Save changes?* (Save, or a red Discard); the web asks to discard them. Built-in themes are copied, never edited.

## Do's and Don'ts

### Do:
- **Do** build clickable things on the shared focus surface (`FocusSurface`, the web's Button and Row) so focus is always the accent fill with on-accent content.
- **Do** name theme roles, never hues, and route every text size through the theme's scale.
- **Do** set every changing number in the condensed cut with tabular figures.
- **Do** show state by form as well as color: solid, hollow and ticked squares; an icon beside the accent for problems.
- **Do** use 2 px corners on controls, 4 px on panels, and a 1 px line edge on panels over video.
- **Do** cut rows, not type size, when a screen runs out of room.
- **Do** put artwork under one flat 80% wash when text sits on it.
- **Do** use the shared `SegmentLadder`, `WatchStrip`, `Poster` and `MediaProgress` for every bar and picture.
- **Do** black out an age-restricted program with its mark, never hide the channel or the cell.

### Don't:
- **Don't** add shadows, glows, blur or tonal tint; gradients only as the player's shades over video.
- **Don't** put a panel, strip or block under the player's OSD, or float its controls outside the banner.
- **Don't** add a theme role that always equals another, or a theme knob no surface would show.
- **Don't** use the danger red for anything but destroying or handing over.
- **Don't** zoom, scale, glow or ring a focused control; fill it (the text field's outline is the one exception).
- **Don't** outline a button or key, in any state.
- **Don't** round past 4 px or draw pills and circles of your own.
- **Don't** change the guide's layout, or draw the now line behind the cells.
- **Don't** put a divider, pill, hover or ripple on the navigation tabs.
- **Don't** name the palette's source character anywhere in the UI.
