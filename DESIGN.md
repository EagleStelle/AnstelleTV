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
  row-gap: "6px"
  panel-phone: "18px"
  panel-tv: "24px"
  rail-phone: "88px"
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
    rounded: "{rounded.control}"
  focus-surface-pressed:
    backgroundColor: "{colors.coal-high}"
    textColor: "{colors.bone}"
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
  button-primary:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
    typography: "{typography.label-large}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "48px"
  button-primary-focused:
    backgroundColor: "{colors.bone}"
    textColor: "{colors.ink}"
  button-secondary:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.label-large}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "48px"
  button-secondary-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
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

The app speaks the television's own on-screen display: the channel banner that drops in when you change channel, the now/next bar, the digits you punch in on the remote, the setup menu with its value on the right. Every screen is built from those parts, on phone, tablet and TV alike, so the app reads as the set itself rather than as a streaming catalog laid over it. It refuses the catalog default of rounded poster rows on navy.

The material is flat and solid. Three values carry the whole world (ink ground, bone type, rose signal), with a few measured steps of the same hue between them for surfaces, lines and secondary text. Depth is a step in that ladder, never a shadow. Corners are nearly square. Focus is not a ring or a zoom: the focused thing fills rose and its text turns ink, exactly as a set-top box highlights a menu line. The palette is drawn from a character's outfit (black-and-white contrast with a pink signal) and that source is never named in the product.

Density follows the screen at hand: 10-foot menus and pages get larger rows, wider padding and bigger digits; phones keep the same parts at hand size. The guide and the player's banner are the exception on TV: they are set tighter than the menus (shorter guide rows, a smaller header, a lower banner), so the guide shows five or six channels and the banner leaves most of the picture clear. Labels never shrink to fit; a screen shows fewer rows instead. Motion is short hard snaps with no bounce.

**Key Characteristics:**
- Ink, bone and rose, with measured steps of one hue (about 352 degrees in OKLCH) between them.
- Focus is a rose fill with ink text, on every control, cell, tab and tile.
- Flat solid fields, hairline edges, near-square corners (2 px controls, 4 px panels).
- Condensed grotesque with tabular figures for numbers, times, titles and field labels.
- State by form as well as color: solid square on air, hollow square up next, dimmed when ended.
- One navigation component on every device.

## Colors

One hue, one signal: every value sits on the same rose-violet axis, from near-black ink to near-white bone, and only rose is allowed to be loud.

### Primary
- **Signal Rose** (rose): focus fills, the guide's now line and playhead, number blocks, on-air markers, the open tab's underline, the primary action, the "TV" in the wordmark and the mark's tile. Text on it is always ink.

### Secondary
- **Deep Rose** (deep-rose): the container step of the accent; selected-not-focused containers and text selection behind the cursor. Paired with **Blush** (blush) as the text that sits on it.

### Neutral
- **Ink** (ink): the ground every screen sits on, the text on any rose fill, and off-air guide cells.
- **Coal** (coal): one step up from the ground; rows, guide cells, panels over video, the navigation bar and rail, text fields.
- **Coal High** (coal-high): the second step; a pressed row on touch and the poster placeholder.
- **Line** (line): hairlines, panel edges, empty ladder segments, the time header's half-hour ticks, empty number-entry slots.
- **Bone** (bone): primary text and the focused fill of the primary button.
- **Muted** (muted): secondary text, unselected tabs, outlines of badges and the selection hairline on an unfocused guide cell. Tinted from the palette, not a neutral grey.

### Named Rules
**The One Hue Rule.** Every color in the theme sits on the same hue; no yellow, grey, blue, violet, orange or red enters the palette, and no stock Tailwind or shadcn value. A new color is a new lightness step on the axis or it does not ship.

**The Rose Means Here Rule.** Rose marks where you are and what is live: focus, now, on air, the one primary action. Body copy and decoration never use it.

**The Role Not Hue Rule.** Code refers to roles (background, surface, surfaceHigh, line, text, textMuted, accent, onAccent, accentContainer, onAccentContainer, guideCell, nowLine, banner, bannerText), never to hues. Themes are JSON in this shape; adding one needs no code change.

## Typography

**Display Font:** Barlow Condensed (bundled static Medium, SemiBold, Bold)
**Body Font:** Barlow (bundled static Regular, Medium, SemiBold, Bold)

**Character:** One grotesque family in two widths. The condensed cut is the OSD's voice (channel numbers, clock times, screen titles, field labels) and always sets tabular figures so digits never shift; the regular width carries titles, body and button labels.

### Hierarchy
- **Display** (Bold/SemiBold, 64/48/36 px): channel numbers in the OSD banner and number entry, OFF AIR, poster placeholder letters.
- **Headline** (SemiBold, 34/30/24 px): page titles (large on TV, medium on phone), the banner's NOW title, panel and back-bar titles, the wordmark.
- **Title** (SemiBold/Medium, 20/16/14 px): menu row titles, tab labels, the NEXT title, channel tile names.
- **Body** (Regular, 16/14/12 px): descriptions, subtitles under rows, program detail. Page subtitles stop at 720 px wide.
- **Label** (SemiBold): label-large in regular width for button labels and row values; label-medium and label-small in the condensed cut, tracked 0.06em, for OSD field labels (NOW, NEXT, the day in the guide header, section headings in uppercase) and badges.

### Named Rules
**The Tabular Rule.** Any number that changes in place (channel number, time, count, progress) sets in the condensed cut with tabular figures.

**The Bundled Rule.** Fonts ship as static files in the APK. No downloadable fonts and no variable axes; the oldest supported TVs cannot use either.

## Layout

All sizes are dp (type in sp), written as px in the tokens. Full pages pad 48 px at the sides on TV and 16 px on phones. Menus are stacks of rows 6 px apart. Rows are at least 64 px tall on TV and 56 px on touch; buttons at least 52 px and 48 px.

The shell has one breakpoint, 600 px wide. Below it, phones put the navigation in a 64 px bar along the bottom. At 600 px and up, tablets get the same tabs as an 88 px rail on the start edge; TV gets an 80 px rail with the same tab sizes as the phone (24 px icon, 11 px label), so it takes no more room than a collapsed drawer. The rail is headed by the mark (32 px on TV, 36 px on tablet).

Settings is a setup menu: on TV and wide screens the menu takes 56% of the width and an info panel explaining the focused row takes the rest, split by a 1 px line; phones get one column. Phone sub-pages (settings pages, channel pages in On demand) open under a 56 px back bar that sits flat on the ground; TV uses the remote's Back instead.

The guide's layout is fixed (preview top left, details beside it, tabs, then the grid) and may only be restyled, with two exceptions the owner asked for: the preview and the details meet with no gap, one band of picture, and on a phone held upright (under 600 px wide) the preview spans the width edge to edge with the details hanging straight under it. The details sit on the selected show's backdrop under the wash, its logo standing in for the title (56 px tall on TV so it reads from the couch, 30 on phones), and keep lines for the overview (three on TV, five on phones) whether or not it fills them, so the grid never moves. Guide rows come in three densities, the middle one by default: 48, 64 and 80 px on touch, 40, 52 and 64 px on TV. On TV the channel column is 148 px wide and an hour spans 300 px. A half-hour tick and time label run along the header. On phones the channel column stacks the number above the name.

The player is full screen with no shell; on phones and tablets it turns the screen sideways and hides the system bars while open. The OSD banner runs along the bottom, inset 12 px on every device so it takes as little of the picture as it can (user decision); number entry sits top-right at the same inset. Controls and track choices live inside the banner, never in a panel of their own. Phones and tablets get the banner sized for the hand, not the TV's copied over (user decision): a dp on a phone held at arm's length looks about as big as one on a TV across the room, but a phone held sideways is only about 410 px tall against the TV's 540, so its banner keeps TV-sized type or smaller and spends no more height than the touch targets need (about 155 px on a Pixel 8 against 226 before, under two fifths of the screen).

On demand is channels, not a catalog, and keeps the guide's margins and gaps so the two screens line up: 32 px sides, 12 px top and bottom, 8 px between bands on TV; 12, 8 and 6 px on phones. The banner sits at the top (inside a 3 px focus frame on TV, edge to edge from the top on phones like the guide's stacked preview, at least 252 px tall on TV and 236 on phones), then one row per guide group in the guide's order, a row header in the condensed field voice (the group's name in bone, the number range it spans muted, 15 px on TV, 13 on phones) a gap more above it than under it, over a row of channel tiles 214 px wide on TV and 172 on phones whose pictures sit one gap apart. Tile pictures, the banner's picture and the headers all start at the side margin; on phones the banner's text does too.

A channel's page, on the same margins and gaps, opens on a hero: the backdrop edge to edge (running out past the page padding and, on phones, under the back bar, which takes the ground's color once the hero scrolls away), with the channel's number block and name, the episode's still (240 px wide on TV, 160 on phones) or a movie's poster, the logo or title (84 px tall on TV, 56 on phones), facts, a status line with its ladder and the overview (three lines on TV, so the watch choices stay on the first screen; all of it on phones). The watch choices follow as one row of keys (see Buttons), with Add to a channel as the last key; then the season tabs and the episode rows. The rest of the page follows on the plain ground under a hard edge.

## Elevation & Depth

The system is flat. There are no shadows, gradients, glows or blurs anywhere, and Material's tonal surface tint is switched off. Depth is conveyed only by stepping up the neutral ladder (ink, coal, coal high) and by a 1 px line edge on panels that sit over video. There are two translucent layers: the guide's past shade, a 20% dark wash over the hours that have already aired, and the backdrop wash, the ground at 80% over a show's or movie's backdrop.

### Named Rules
**The Wash Not Fade Rule.** Artwork under text takes one flat wash of the ground, never a gradient, scrim fade or blur. 80% keeps bone text at 9:1 and muted text above 4.5:1 even over a white sky, and tints the art toward ink instead of grey.

**The Step Not Shadow Rule.** Anything that needs to read as above something else moves one step up the surface ladder and, over video, takes a line-colored hairline. It never casts a shadow or blurs what is behind it.

**The No Zoom Rule.** Focus never scales, glows or lifts. Compose for TV's default focus scale and glow are off.

**The Fill Not Frame Rule** (user decision). A button or key is a fill, never a frame: no outline at rest, on focus or on press. It reads apart from the ground by its fill alone (coal, or rose for the primary), the way a menu row does, and every state change is a change of fill. Hairlines belong to panels over video, empty entry slots, badges and the guide's cells, never to anything you press as a button.

## Shapes

Near-square corners everywhere: 2 px on controls, rows, guide cells, posters, buttons, badges and number blocks; 4 px on panels over video and dialogs. Edges are 1 px hairlines in line color (panels, off-air cells, empty entry slots) or muted (badges, the unfocused selection in the guide); buttons and keys never take one (see The Fill Not Frame Rule). Markers are squares, not dots: the on-air marker is a solid 8 px square, up next the same square hollow with a 1.5 px stroke, and the now line's head in the time header is an 8 px square on a 2 px stem. Progress is a ladder of rectangular segments with 2 px gaps.

The mark is a channel bug: an ink "A" on a rose tile with a small corner (6 units on a 108-unit grid), its crossbar running on past the right leg the way the now line runs out of frame. The in-app mark and the generated launcher icon and TV banner share the same geometry.

## Components

### Buttons
Flat, solid and labelled; the OSD's action keys. Fills only, never outlines.
- **Shape:** near-square (2 px).
- **Primary:** rose with ink label, 48 px tall on touch and 52 px on TV, 20 / 24 px side padding, optional 20 px leading icon. One per screen.
- **Focus:** the primary turns bone (it is already rose, so focus has to show as something else); a secondary turns rose with ink text.
- **Secondary:** coal with bone label, no outline. A touch press steps it up to coal high.
- **Watch key:** a channel page's ways to watch, plus Add to a channel, as one row of keys of one width (84 by 52 px on TV, 72 by 44 on touch, narrower on phones too small for five): the icon centered on a coal key with no outline, its label under it in uppercase label-medium. The key and label take the press together; focus fills the key rose with an ink icon and turns the label rose. On touch the first key is the primary, rose with an ink icon.

### Cards / Containers
- **OSD panel:** coal with a 1 px line edge, 4 px corners, 24 px padding on TV and 18 px on touch, children 10 px apart. Used for messages over video and the action dialog.
- **Action dialog:** an OSD panel at most 560 px wide over the screen under the 80% wash: a header saying what (the number block and title, then the episode and channel in muted body) and when (a status line), then one menu row per choice, each with a line on what it does. Focus starts on the first row; Back or a tap outside closes it, so there is no Close row. Used for the guide's program actions and an episode held in On demand.
- **Hero:** a backdrop under the wash, edge to edge, holding a show's or movie's title block; see Layout.
- **Channel tile:** a transparent card with a 3 px inset: the program's 16:9 backdrop under the wash with the show's logo (40 px tall on TV, 32 on phones; the title in condensed type when there is none) and a 16-segment ladder of the airing at its foot, then the caption: a small number block (label-large on rose, ink digits) and the channel's name in title-small, then the time left and the episode in muted body-small. Focus fills the whole card rose, so the picture sits in a rose frame over a rose caption, and the number block turns ink with rose digits so it still reads. Off air, the picture is coal high with OFF AIR in muted label-medium and the caption says when it is back.
- **Status line:** the banner's NOW line on its own: the marker (solid on now, hollow for later or up next, none once aired), the field label in rose (muted once aired), then the times and time left in muted, all label-medium uppercase. It heads the banner slide, the channel page's hero, an action dialog and the on-air episode row.
- **Episode row:** a coal row: the 16:9 still (176 px on TV, 124 on phones) with a 12-segment ladder of the minutes watched over a strip of the wash at its foot, then the episode in title-medium, its length and minutes watched (for the episode on the channel, the status line in their place, never a label above the title), and two lines of overview, both muted. Focus fills it rose.
- **Pager:** one 8 px square per banner slide, 5 px apart: the slide showing is solid rose (bone while the banner is focused), the rest hollow muted.
- **Shadow Strategy:** none (see Elevation & Depth).

### Inputs / Fields
- **Style:** Material 3 outlined field themed to the world: coal fill, 1 px line outline, 2 px corners, body-large text, muted label.
- **Focus:** the outline and label turn rose, the cursor is rose, selection sits on deep rose.
- **Disabled:** ink fill, muted text.
- On TV the D-pad focuses the field and OK opens the keyboard; up and down move out of it.

### Navigation
One component on every device, styled like YouTube's or Spotify's tabs rather than Material's: an icon over a short label, on the coal step so it reads apart from the page. No divider, no pill indicator, no hover and no ripple. The open tab shows the filled icon and a bone label in SemiBold; the rest show the outlined icon and a muted label in Medium. A press briefly shrinks the icon. Phones get a bottom bar; tablets and TV get the same tabs in a rail headed by the mark. On TV the D-pad's focus fills the tab rose with ink content, and entering the rail lands on the open destination. Destinations are Live TV, On demand, Dashboard, Settings and Profiles.

Inside a screen, tabs are flat text in the condensed cut with an optional tabular count; the open one has a 2 px rose underline exactly as wide as its label; focus fills it rose.

### Menu Row
The setup menu's line, and the most-used component. Icon, title and an optional muted description on the left; the current value on the right in label-large. On TV a choice value steps with left and right (and forward with OK), and small arrows flank it while focused. A chosen item in a list shows a rose check; a drill-in row shows a muted chevron. Focus fills the row rose with ink text and 76% ink for the description. A pressed row on touch steps up to coal high. On touch, where nothing has focus to lead with, the screen's one main choice (Watch live on a channel's page, the first action in a dialog) is a primary row: rose with ink text, turning bone when focused, like the primary button; TV leaves it to focus.

On touch, an on/off row shows the platform Material 3 Switch (themed: rose track and ink thumb when on, coal-high track and muted thumb when off) and a multi-value row opens a Material 3 AlertDialog on coal listing the choices. Both are platform components themed into the world; TV shows the value text and steps it instead.

### Channel Banner (signature)
The OSD banner over video: a coal strip with a line edge, laid out as two squares of one size with the program's column between them. The squares are as tall as the banner, and the banner is as tall as the column needs at the width the squares leave it. The start square is the rose block: the channel number in display type (medium on TV, small on phones) with the channel name under it in label-medium. Then the program's column: the NOW line (solid on-air square, NOW in rose, the time range in muted) with the tech line at its right end, the program's logo (56 px tall on TV, 28 on phones) or its title in headline type (medium on TV, small on phones), the detail in muted body-medium (S1E2 · its title for an episode; under a movie's logo, 2025 · its title), the progress row and the keys. Each player has this one banner (user decision): it snaps in on every tune and seek with its keys showing, and focus stays on the picture until OK, Info or Menu moves it into the keys. On TV and screens at least 720 px wide the end square, behind a 1 px line, holds NEXT: hollow square and start time, the next program's logo (44 px tall on TV, 24 on phones) or title in title-medium, then the same detail line in body-small; a narrower phone leaves NEXT out so the column keeps its width. It snaps in from below in 140 ms with no bounce. The on-demand banner is the same column without the number block or NEXT column (ON DEMAND or PAUSED in rose with the tech line beside it, NEXT as a line: its own row on TV, at the right end of the detail line on phones and tablets).
- **Progress row:** the time in (bone, tabular), a 40-segment ladder, the full length (muted, tabular), the same in both players on every device. On touch screens the ladder sits in a 24 px touch strip, which Compose widens to the 48 px minimum target: tap a point or drag along it, the time in follows the finger, and the seek lands on release. The remote seeks with left and right.
- **Tech line:** at the right end of the NOW line on every device (user decision), where a set-top banner prints its format icons, so it costs the banner no row of its own. Read from the top of the chain down: the server (a server icon and its name in bone), a short hairline, how it plays (DIRECT PLAY or TRANSCODING in bone label-medium), then quality, video and audio as OSD badges. NOW and the time hold still; a tech line too long for the room left scrolls through it as a marquee, clipped hard at its edges, for as long as the banner is up.
- **Keys:** the program column's last row, under a hairline below the progress row, always on screen with the banner. On touch the keys' own height is the air under the hairline and at the banner's foot. Unfocused they read as the banner's legend; focused, the banner holds until Back. Each key is flat on the banner with an 18 px icon and a condensed label, 40 px tall on TV and 48 on touch; focus fills it rose with ink. Track keys show their icon and the track in use (JAPANESE, OFF) instead of a label. The track keys anchor to the column's left and the player's own actions to its right: RESTART on a channel; on demand the transport in the remote's order, PREVIOUS, 10 S, PAUSE, 30 S, NEXT, pause in the middle. A key that can't act (previous on a show's first episode, next on its last) keeps its place in muted ink at 45% and focus passes over it, so the row never shifts; a movie has neither. The row runs one key-padding past the column on each side, so the first and last labels line up with the text above. Opening a track key swaps the row for its choices, the field's name in rose and the track in use checked. No channel keys: phones swipe to surf.

### Number Entry (signature)
Up to four digit slots top-right over video. Each typed digit drops onto its own rose number block; empty slots are hollow with a line edge. A 16-segment ladder under them drains linearly over two seconds, then the channel tunes and the banner snaps in.

### Guide Grid
Cells are coal fills with 2 px corners, title in SemiBold body type (14 px on TV, 15 on touch) and the detail muted (11 px on TV, 12 on touch). The focused cell fills rose with ink title and detail; when focus is elsewhere the selected cell keeps a 1 px muted outline. Off-air cells drop to ink with a line edge and muted text. A cell narrower than 44 px shows only its fill, never a stack of ellipses. The program on air carries a small rose square. The now line is a rose hairline drawn over the cells (not behind them), continuing a playhead in the time header; the aired hours to its left sit under the past shade.

### OSD Badge
A fact such as 4K, HDR10 or 5.1: condensed label-small in bone, inside a 1 px muted outline at 60%, never filled.

### Title Logo
A show's or movie's logo from the server stands in for its title in the guide's details, page heroes and both players' banners, fitted into a fixed-height slot aligned bottom-start, the title kept for screen readers. The slot holds its height while the logo loads, and the title returns as text if it fails, so nothing under it moves.

## Do's and Don'ts

### Do:
- **Do** build every clickable thing on the shared focus surface, so focus is always a rose fill with ink content and touch gets a ripple only where the surface allows it.
- **Do** keep the palette to ink, coal, coal high, line, bone, muted, rose and deep rose (with blush on deep rose).
- **Do** set every changing number in the condensed cut with tabular figures.
- **Do** show state by form as well as color: solid square on air, hollow square up next, a warning icon beside rose for problems.
- **Do** use 2 px corners on controls and 4 px on panels, and a 1 px line edge on anything over video.
- **Do** cut rows, not type size, when a screen runs out of room.
- **Do** put artwork under one flat 80% wash of the ground when text sits on it.
- **Do** keep transitions short and hard (about 140 to 180 ms), with no overshoot.
- **Do** use the one navigation component on phone, tablet and TV, with Live TV as the first destination.

### Don't:
- **Don't** use shadows, gradients, glow or blur, or Material's surface tint; not even a fade under a backdrop.
- **Don't** float player controls in a panel of their own; they belong in the banner's program column.
- **Don't** add yellow, grey, blue, violet, orange or red, stock Tailwind or shadcn values, or a terracotta-and-cream scheme.
- **Don't** zoom, scale, glow or ring a focused control; fill it. The text field is the one exception: as a platform field its outline turns rose.
- **Don't** outline a button or key, in any state; it is a fill.
- **Don't** round corners past 4 px or draw pill or circle controls of your own; the platform Material 3 Switch on touch is the only pill.
- **Don't** change the guide's layout; restyle only.
- **Don't** move the now line behind the cells.
- **Don't** put a divider, pill indicator, hover or ripple on the navigation tabs.
- **Don't** name the palette's source character anywhere in the UI.
