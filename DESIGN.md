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
  settings-row:
    textColor: "{colors.bone}"
    typography: "{typography.body-large}"
    padding: "10px 16px"
    height: "56px"
  settings-row-focused:
    backgroundColor: "{colors.rose}"
    textColor: "{colors.ink}"
  settings-panel:
    backgroundColor: "{colors.coal}"
  text-field:
    backgroundColor: "{colors.coal}"
    textColor: "{colors.bone}"
    typography: "{typography.body-large}"
    rounded: "{rounded.control}"
---

# Design System: AnstelleTV

## Overview

**Creative North Star: "On-Screen Display"**

The app speaks the television's own on-screen display: the channel banner that drops in when you change channel, the now/next bar, the digits you punch in on the remote. Every screen is built from those parts, and Settings alone speaks the device's own settings instead (see Settings Rows), on phone, tablet and TV alike, so the app reads as the set itself rather than as a streaming catalog laid over it. It refuses the catalog default of rounded poster rows on navy.

The material is flat and solid. Three values carry the whole world (ink ground, bone type, rose signal), with a few measured steps of the same hue between them for surfaces, lines and secondary text. Depth is a step in that ladder, never a shadow. Corners are nearly square. Focus is not a ring or a zoom: the focused thing fills rose and its text turns ink, exactly as a set-top box highlights a menu line. The palette is drawn from a character's outfit (black-and-white contrast with a pink signal) and that source is never named in the product.

Density follows the screen at hand: 10-foot menus and pages get larger rows, wider padding and bigger digits; phones keep the same parts at hand size. The guide and the player's banner are the exception on TV: they are set tighter than the menus (shorter guide rows, a smaller header, a lower banner), so the guide shows five or six channels and the banner leaves most of the picture clear. Labels never shrink to fit; a screen shows fewer rows instead. Motion is short hard snaps with no bounce.

**Key Characteristics:**
- Ink, bone and rose, with measured steps of one hue (about 352 degrees in OKLCH) between them.
- Focus is a rose fill with ink text, on every control, cell, tab and tile.
- Flat solid fields, hairline edges, near-square corners (2 px controls, 4 px panels).
- Condensed grotesque with tabular figures for numbers, times, titles and field labels.
- State by form as well as color: solid square on air, hollow square up next, ticked square watched, dimmed when ended.
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

**The Danger Rule** (user decision). One color stands outside every theme: the danger red, #D32F2F with white on it (`DangerColor`, `OnDangerColor`). It marks what destroys or hands over (delete, remove, transfer ownership), the same in every theme: a destructive button is filled with it at rest on every device and turns white with red ink on focus; a destructive row or menu entry carries it on its icon and fills with it on focus; the answer to a destructive question is the red button. Nothing else uses it, and it is never a theme role.

**The Rose Means Here Rule.** Rose marks where you are and what is live: focus, now, on air, the one primary action. Body copy and decoration never use it.

**The Role Not Hue Rule.** Code refers to roles (background, surface, surfaceHigh, line, text, textMuted, accent, onAccent, accentContainer, onAccentContainer, guideCell, nowLine, banner, bannerText), never to hues. Themes are JSON in this shape; adding one needs no code change.

## Typography

**Display Font:** Barlow Condensed (bundled static Medium, SemiBold, Bold)
**Body Font:** Barlow (bundled static Regular, Medium, SemiBold, Bold)

**Character:** One grotesque family in two widths. The condensed cut is the OSD's voice (channel numbers, clock times, screen titles, field labels) and always sets tabular figures so digits never shift; the regular width carries titles, body and button labels.

### Hierarchy
- **Display** (Bold/SemiBold, 64/48/36 px): channel numbers in the OSD banner and number entry, the full-screen stand-in mark (OFF AIR, AGE RESTRICTED, NO ACCESS), poster placeholder letters.
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

Settings follow the device's own settings (user decision). On TV and touch screens 840 px and wider, Google TV's guided panel: the guidance on the ground takes 42% (40 px in from the rail, 40 px down: the breadcrumb in muted body-medium joined by drawn chevrons, the title in display-small, the page's note behind an info mark in muted body-medium, then what the page is about, such as a preview or a readout, 28 px further down); the list takes the rest on a coal panel edge to edge in height, 12 px in from its sides and 40 px from its top on TV, rows 2 px apart. Phones get Android's settings: on sub-pages a back arrow in a 64 px bar with the large title in headline-medium straight under it (the bar 120 px tall expanded), collapsing into the bar as the list scrolls; on the home, with no arrow, the title in headline-medium is the 64 px bar itself, so no empty band sits above it (user decision); rows edge to edge on the ground, and the page's note closing the list beside an info mark. Other phone sub-pages (channel pages in On demand) open under a 56 px back bar that sits flat on the ground; TV uses the remote's Back instead.

The guide's layout is fixed (preview top left, details beside it, tabs, then the grid) and may only be restyled, with two exceptions the owner asked for: the preview and the details meet with no gap, one band of picture, and on a phone held upright (under 600 px wide) the preview spans the width edge to edge with the details hanging straight under it. The details sit on the selected show's backdrop under the wash, its logo standing in for the title (56 px tall on TV so it reads from the couch, 30 on phones), and keep lines for the overview (three on TV, five on phones) whether or not it fills them, so the grid never moves. Guide rows come in three densities, the middle one by default: 48, 64 and 80 px on touch, 40, 52 and 64 px on TV. On TV the channel column is 148 px wide and an hour spans 300 px. A half-hour tick and time label run along the header. On phones the channel column stacks the number above the name. Once any channel has a logo the column widens on every tab alike (172 px on TV, 132 on phones), and a channel with one shows it at the column's start in its standard box with the number over the name beside it (see Channel Logo). The details carry the described program's channel in their top-end corner, on the backdrop like the player's ident: the logo with the number over the name beside it, or the number over the name alone, never taller than the title's slot, so the time line no longer names the channel.

The player is full screen with no shell; on phones and tablets it turns the screen sideways and hides the system bars while open. Nothing in the player sits on a panel (user decision: it should look like real TV, the picture unobstructed). The OSD banner lies straight on the picture along the bottom over the edge shade, its text at the TV's title-safe margins (48 px at the sides, 24 at the foot; 24 at the sides on phones, whose touch keys close the banner). The channel's ident (logo, number, name) sits in the top-end corner on the corner shade, at 48 by 24 px on TV, and number entry takes that same corner while digits are typed. Controls and track choices live inside the banner, never in a panel of their own. While the banner is up the subtitles step above it, the way a streaming player's captions clear its controls, and settle back when it goes. Phones and tablets get the banner sized for the hand, not the TV's copied over (user decision): a dp on a phone held at arm's length looks about as big as one on a TV across the room, but a phone held sideways is only about 410 px tall against the TV's 540, so its banner keeps TV-sized type or smaller and spends no more height than the touch targets need (about 155 px on a Pixel 8 against 226 before, under two fifths of the screen).

On demand is channels, not a catalog, and keeps the guide's margins and gaps so the two screens line up: 32 px sides, 12 px top and bottom, 8 px between bands on TV; 12, 8 and 6 px on phones. The banner sits at the top (inside a 3 px focus frame on TV, edge to edge from the top on phones like the guide's stacked preview, at least 252 px tall on TV and 236 on phones), then one row per guide group in the guide's order, a row header in the condensed field voice (the group's name in bone, the number range it spans muted, 15 px on TV, 13 on phones) a gap more above it than under it, over a row of channel tiles 214 px wide on TV and 172 on phones whose pictures sit one gap apart. Tile pictures, the banner's picture and the headers all start at the side margin; on phones the banner's text does too.

A channel's page, on the same margins and gaps, opens on a hero: the backdrop edge to edge (running out past the page padding and, on phones, under the back bar, which takes the ground's color once the hero scrolls away), with the channel's number block and name, the episode's still (240 px wide on TV, 160 on phones) or a movie's poster, the logo or title (84 px tall on TV, 56 on phones), facts, a status line with its ladder and the overview (three lines on TV, so the watch choices stay on the first screen; all of it on phones). The watch choices follow as one row of keys (see Buttons), with Add to a channel as the last key; then the season tabs and the episode rows. The rest of the page follows on the plain ground under a hard edge.

## Elevation & Depth

The system is flat. There are no shadows, gradients or glows anywhere, and no blur apart from an age-restricted program's title and logo (user decision); Material's tonal surface tint is switched off. Depth is conveyed only by stepping up the neutral ladder (ink, coal, coal high) and by a 1 px line edge on panels that sit over video. There are two translucent layers outside the player: the guide's past shade, a 20% dark wash over the hours that have already aired (and over the stills of episodes watched through), and the backdrop wash, the ground at 80% over a show's or movie's backdrop. Over live video the players use shades instead (see The Shade Over Picture Rule).

### Named Rules
**The Wash Not Fade Rule.** Artwork under text takes one flat wash of the ground, never a gradient, scrim fade or blur. 80% keeps bone text at 9:1 and muted text above 4.5:1 even over a white sky, and tints the art toward ink instead of grey.

**The Shade Over Picture Rule** (user decision). The player's OSD sits on the picture itself, never on a panel, and the banner tone fades in under it only where text needs it: the edge shade rises from the screen's bottom edge, gone 96 px above the banner on TV (48 on phones), 70% at the banner's first line and 88% at its foot; the corner shade is a quarter ellipse from the top-end corner behind the ident, reaching 1.25 times the ident's diagonal, 88% in the corner; a message over the picture (Tuning…, a notice) sits on a soft elliptical shade. They are the only gradients in the product, and only over video: they keep bone text readable over a white sky while the picture runs on under the banner, as a broadcast's own graphics do. They never take an edge, a corner or a fill of their own.

**The Step Not Shadow Rule.** Anything that needs to read as above something else moves one step up the surface ladder and, over video, takes a line-colored hairline. It never casts a shadow or blurs what is behind it.

**The No Zoom Rule.** Focus never scales, glows or lifts. Compose for TV's default focus scale and glow are off.

**The Fill Not Frame Rule** (user decision). A button or key is a fill, never a frame: no outline at rest, on focus or on press. It reads apart from the ground by its fill alone (coal, or rose for the primary), the way a menu row does, and every state change is a change of fill. Hairlines belong to panels over video, empty entry slots, badges and the guide's cells, never to anything you press as a button.

## Shapes

Near-square corners everywhere: 2 px on controls, rows, guide cells, posters, buttons, badges and number blocks; 4 px on panels over video and dialogs. Edges are 1 px hairlines in line color (panels, off-air cells, empty entry slots) or muted (badges, the unfocused selection in the guide); buttons and keys never take one (see The Fill Not Frame Rule). Markers are squares, not dots: the on-air marker is a solid 8 px square, up next the same square hollow with a 1.5 px stroke, watched through a 10 px bone square with a tick cut through it, and the now line's head in the time header is an 8 px square on a 2 px stem. Progress is a ladder of rectangular segments with 2 px gaps.

The mark is a channel bug: an ink "A" on a rose tile with a small corner (6 units on a 108-unit grid), its crossbar running on past the right leg the way the now line runs out of frame. The in-app mark and the generated launcher icon and TV banner share the same geometry.

## Components

### Buttons
Flat, solid and labelled; the OSD's action keys. Fills only, never outlines.
- **Shape:** near-square (2 px).
- **Primary:** rose with ink label at rest on every device (user decision: TV and phone buttons match), 48 px tall with 20 px side padding on touch, 52 px and 24 px on TV, optional 20 px leading icon; one per screen.
- **Focus:** a button turns rose with ink text; the touch primary, already rose, turns bone.
- **Secondary:** coal with bone label, no outline; in Settings and dialogs it is coal high on every device (it has to step up off the TV's coal panel, and the phone's settings list wears the same fill so the two match). A touch press steps it up one more.
- **In Settings** (user decision, TV and phone alike): buttons share their line evenly, the label and icon centred. One alone spans the list's width, a destructive one too; two split it half and half with 8 px between them. When a label wouldn't fit its share the buttons stack 8 px apart, each across the list.
- **Watch key:** a channel page's ways to watch, plus Add to a channel, as one row of keys of one width (84 by 52 px on TV, 72 by 44 on touch, narrower on phones too small for five): the icon centered on a coal key with no outline, its label under it in uppercase label-medium. The key and label take the press together; focus fills the key rose with an ink icon and turns the label rose. On touch the first key is the primary, rose with an ink icon.

### Cards / Containers
- **OSD panel:** coal with a 1 px line edge, 4 px corners, 24 px padding on TV and 18 px on touch, children 10 px apart. Used for the action dialog; the players' messages sit on a soft shade instead (see The Shade Over Picture Rule).
- **Action dialog:** an OSD panel at most 560 px wide over the screen under the 80% wash: a header saying what (the number block and title, then the episode and channel in muted body) and when (a status line), then one menu row per choice, each with a line on what it does. Focus starts on the first row; Back or a tap outside closes it, so there is no Close row, and the D-pad never leaves it. Used for the guide's program actions and an episode held in On demand.- **Hero:** a backdrop under the wash, edge to edge, holding a show's or movie's title block; see Layout.
- **Channel tile:** a transparent card with a 3 px inset: the program's 16:9 backdrop under the wash with the show's logo (40 px tall on TV, 32 on phones; the title in condensed type when there is none) and a 16-segment ladder of the airing at its foot, then the caption: a small number block (label-large on rose, ink digits) and the channel's name in title-small, then the time left and the episode in muted body-small. Focus fills the whole card rose, so the picture sits in a rose frame over a rose caption, and the number block turns ink with rose digits so it still reads. Off air or restricted, the picture is the ground with the line stand-in mark at its top start, and the caption says UNTIL … in label-medium (see Stand-in Mark).
- **Status line:** the banner's NOW line on its own: the marker (solid on now, hollow for later or up next, none once aired), the field label in rose (muted once aired), then the times and time left in muted, all label-medium uppercase. It heads the banner slide, the channel page's hero, an action dialog and the on-air episode row.
- **Episode row:** a coal row: the 16:9 still (176 px on TV, 124 on phones) with the watch strip at its foot (see Watched), then the episode in title-medium (muted once watched through), its length, or the time left once started on demand ("12 min left"), and two lines of overview, both muted. The episode on the channel reads the same: the list never carries a live status line, which stays in the hero. Focus fills it rose.
- **Pager:** one 8 px square per banner slide, 5 px apart: the slide showing is solid rose (bone while the banner is focused), the rest hollow muted.
- **Shadow Strategy:** none (see Elevation & Depth).

### Inputs / Fields
- **Style:** Material 3 outlined field themed to the world: coal fill, 1 px line outline, 2 px corners, body-large text, muted label.
- **Focus:** the outline and label turn rose, the cursor is rose, selection sits on deep rose.
- **Disabled:** ink fill, muted text.
- On TV the D-pad focuses the field and OK opens the keyboard; up and down move out of it.

### Navigation
One component on every device, styled like YouTube's or Spotify's tabs rather than Material's: an icon over a short label, on the coal step so it reads apart from the page. No divider, no pill indicator, no hover and no ripple. The open tab shows the filled icon and a bone label in SemiBold; the rest show the outlined icon and a muted label in Medium. A press briefly shrinks the icon. Phones get a bottom bar; tablets and TV get the same tabs in a rail headed by the mark. On TV the D-pad's focus fills the tab rose with ink content, and entering the rail lands on the open destination. Destinations are Live TV, On demand, Dashboard (only while the profile has it on), Settings and Profiles.

Inside a screen, tabs are flat text in the condensed cut with an optional tabular count; the open one has a 2 px rose underline exactly as wide as its label; focus fills it rose.

### Settings Rows
Settings speak the device's own settings, and every control says what it does by what sits at its end (user decision). A row lies flat on its ground with no fill at rest: a muted 24 px icon (or a radio, a checkbox, a tabular number, a swatch), the title in body-large Medium, the state under it in muted body-medium, then the end mark. Focus fills the row rose with ink content and 76% ink for the state; touch gets a ripple. Rows are at least 56 px tall, 72 px on phones with a second line.
- **Chevron:** opens a page.
- **Switch:** on or off, the whole row flips it; the platform Material 3 Switch on TV as well as touch (on a focused row, an ink track with a rose thumb).
- **Drop-down mark under the value:** opens the choices as a radio list, each choice with a line on what it does.
- **Pencil:** opens the value for typing.
- **Overflow mark:** opens an item's own menu (rename, remove). When the row has an action of its own (a theme's radio puts it on), the mark is a key of its own at the row's end, right of the row on the D-pad.
- **Drag handle after the overflow mark:** puts a list in order (the languages, Live TV's groups), as Android's language list does. On touch it is a 48 px handle in muted: touching it lifts the row at once to coal high (a step, never a shadow) and the row follows the finger over the others, which glide out of its way in 160 ms; the list scrolls on at its edges, and a row let go glides into its place. On TV it is a 56 px key of its own, right of the row: focus fills it rose; OK picks the row up and the handle turns to an up and a down arrow (one that can't go further at 45%), up and down carry the row a place at a time, snapping, and OK or Back sets it down.
- **Radio or checkbox at the start:** selects, one or many.
- **Nothing:** a fact; nothing presses it and it takes no focus.

The ends were chosen so a user can tell every kind apart at a glance, even where native settings leave a value or a text entry bare (user decision). Verbs are buttons sharing their line evenly (see Buttons), never rows. A destructive verb is a button at the page's foot, apart under no heading, that asks first; Backup's *Restore from file* is the one exception, beside *Save backup* at the top (user decision: the two file verbs go together). Section headings are the OSD field label (muted, condensed, uppercase) with 24 px above and 4 px under; groups take standard names, and the user's own things are always *Custom* (Custom channels, Custom segments, Custom themes) above what the app made. A search field with a search mark heads any list too long to scan (channels, themes). Explanations live only in the page's info note, never inline; inline lines are kept for problems, status and empty lists.

**Readouts.** On TV the Settings home's guidance shows the focused area at a glance, never as a paragraph: Themes its preview, every other area a readout in the server page's language, a hairline frame with no fill holding a status line, headings in the field voice behind the icon that names them with their setting at the end in bone, figures in tabular digits over their names (the lead one in display-small), facts behind their icons, ordered lists ranked in tabular digits, and checks (a rose check or a muted dash) for what is kept or sent.

**Sheets** open over their page. On TV they are Google TV's next panel: the guidance names the setting, the panel holds the radio list, the menu, the field with Save and Cancel, or a question with its answer and Cancel, both as buttons sharing one line, the answer to a destructive question red (focus starts on Cancel); Back closes it and focus returns to what opened it. On phones they are Android's dialog on coal with 4 px corners and text buttons (the answer in rose, Cancel in bone), and an item's menu is the overflow menu under its row, coal with a line edge.

### Menu Row
The line of an action dialog and the other OSD lists outside Settings. Icon, title and an optional muted description on the left; the current value on the right in label-large. On TV a choice value steps with left and right (and forward with OK), and small arrows flank it while focused. A chosen item in a list shows a rose check; a drill-in row shows a muted chevron. Focus fills the row rose with ink text and 76% ink for the description. A pressed row on touch steps up to coal high. On touch, where nothing has focus to lead with, the screen's one main choice (Watch live on a channel's page, the first action in a dialog) is a primary row: rose with ink text, turning bone when focused, like the primary button; TV leaves it to focus.

On touch, an on/off row shows the platform Material 3 Switch (themed: rose track and ink thumb when on, coal-high track and muted thumb when off) and a multi-value row opens a Material 3 AlertDialog on coal listing the choices. Both are platform components themed into the world; TV shows the value text and steps it instead.

### Channel Banner (signature)
The OSD banner straight on the picture over the edge shade, with no strip, edge or block under it (user decision): the program's column, then NEXT in a column of its own (240 px on TV, 200 on touch) behind a 1 px line, as tall as the program's column. The channel is named by its ident in the top-end corner: the channel's logo (96 px tall on TV, 56 on phones, end-aligned in its standard box) over the number in rose display type (medium on TV, small on phones) over the name in bone label-medium, set to the corner, coming and going with the banner. The program's column holds the NOW line (solid on-air square, NOW in rose, the time range in muted) with the tech line at its right end, the program's logo (56 px tall on TV, 28 on phones) or its title in headline type (medium on TV, small on phones), the detail in muted body-medium (S1E2 · its title for an episode; under a movie's logo, 2025 · its title), the progress row and the keys. Each player has this one banner (user decision): it snaps in on every tune and seek with its keys showing, and focus stays on the picture until OK, Info or Menu moves it into the keys. On TV and screens at least 720 px wide the NEXT column, its first line level with NOW, holds the hollow square and start time, the next program's logo (44 px tall on TV, 24 on phones) or title in title-medium, then the same detail line in body-small; a narrower phone leaves NEXT out so the column keeps its width. It snaps in from below in 140 ms with no bounce. The on-demand banner is the same column with no ident and no NEXT column or line (ON DEMAND or PAUSED in rose, then the clock range the program would run if played straight through, in the NOW line's label-medium muted, with the tech line at the row's end; pausing moves the range). Its top corners, empty of an ident, hold one banner key each on a corner shade, at the ident's margins less the key's own padding, coming and going with the banner: ← ON DEMAND (a back arrow) at the top start, the shade mirrored, and EPISODES (a list mark) at the top end for an episode. EPISODES swaps the key row for the episode strip under the hairline, the way a track key swaps it for its choices: EPISODES in rose, then the season tabs (compact, the playing season behind the on-air square), then a row of cards scrolling sideways, a channel tile's way: a 3 px inset holding the 16:9 still (174 px wide on TV, 132 on touch, the watch strip at its foot; see Watched), the episode in title-small, then PLAYING in rose behind the solid square for the one playing, or its length (the time left once started) in muted body-small. Focus fills the whole card rose with ink.
- **Progress row:** the time in (bone, tabular), a 40-segment ladder, the full length (muted, tabular), the same in both players on every device. On touch screens the ladder sits in a 24 px touch strip, which Compose widens to the 48 px minimum target: tap a point or drag along it, the time in follows the finger, and the seek lands on release. The remote seeks with left and right.
- **Tech line:** at the right end of the NOW line on every device (user decision), where a set-top banner prints its format icons, so it costs the banner no row of its own. Read from the top of the chain down: the server (a server icon and its name in bone), a short hairline, how it plays (DIRECT PLAY or TRANSCODING in bone label-medium), then quality, video and audio as OSD badges. NOW and the time hold still; a tech line too long for the room left scrolls through it as a marquee, clipped hard at its edges, for as long as the banner is up.
- **Keys:** the program column's last row, under a hairline below the progress row, always on screen with the banner. On touch the keys' own height is the air under the hairline and at the banner's foot. Unfocused they read as the banner's legend; focused, the banner holds until Back. Each key is flat on the banner with an 18 px icon and a condensed label, 40 px tall on TV and 48 on touch; focus fills it rose with ink. Track keys show their icon and the track in use (JAPANESE, OFF) instead of a label. The track keys anchor to the column's left and the player's own actions to its right: RESTART on a channel; on demand the transport in the remote's order, PREVIOUS, 10 S, PAUSE, 30 S, NEXT, pause in the middle. A key that can't act (previous on a show's first episode, next on its last) keeps its place in muted ink at 45% and focus passes over it, so the row never shifts; a movie has neither. The row runs one key-padding past the column on each side, so the first and last labels line up with the text above. Opening a track key swaps the row for its choices, the field's name in rose and the track in use checked. No channel keys: phones swipe to surf.

### Number Entry (signature)
Up to four digit slots in the ident's top-end corner, on the corner shade, standing in for the ident while digits are typed, the way a set's channel number turns into the digits being punched in. Each typed digit sets in the ident's own type, rose display; a slot still to come is a 3 px muted bar on the digit's baseline. A 16-segment ladder under them drains linearly over two seconds, then the channel tunes, the banner snaps in and the new channel's ident takes the corner.

### Guide Grid
Cells are coal fills with 2 px corners, title in SemiBold body type (14 px on TV, 15 on touch) and the detail muted (11 px on TV, 12 on touch). The focused cell fills rose with ink title and detail; when focus is elsewhere the selected cell keeps a 1 px muted outline. Off-air and restricted cells drop to ink with a line edge, muted text and their icon (see Stand-in Mark). A cell narrower than 44 px shows only its fill, never a stack of ellipses. The program on air carries a small rose square. The now line is a rose hairline drawn over the cells (not behind them), continuing a playhead in the time header; the aired hours to its left sit under the past shade.

### Channel Logo
A channel's own logo, uploaded for a channel someone built (library channels take none), shown in one standard box everywhere: 5 wide by 3 tall, the shape set-top picons use, the logo fitted inside it with its proportions kept, so every channel's mark carries the same weight. It never takes a plate, frame or fill of its own and is silent to screen readers, since the name always sits beside it. It loads with no crossfade, so a guide row never flickers. Sizes: the guide's channel column, centered, its height the row's less its air (22 to 36 px on TV, 22 to 28 on phones); the details' corner, as tall as the title's slot (56 px on TV, 30 on phones); the player's ident, end-aligned, 96 px on TV and 56 on phones.

Uploads are made standard once, when stored: decoded no larger than needed, their transparent margins trimmed (an opaque logo stays whole, since its plate is part of it), fitted inside 400 by 240 px and never enlarged, then saved lossless, as WebP where the device encodes it lossless and as PNG before Android 10. Logos stored before this are made again once at launch.

### OSD Badge
A fact such as 4K, HDR10 or 5.1: condensed label-small in bone, inside a 1 px muted outline at 60%, never filled.

### Watched (an episode aired, for you)
An episode watched through (marked played in Jellyfin, or left past 95%) speaks the guide's language for the past, never the accent: rose means here and now, and this is done. Every pair below is one the theme floors already guarantee, so it holds in every theme, light ones included.
- **Watched square:** the third of the air marker's squares (solid on air, hollow up next): a 10 px square in bone with a tick cut through it in the ground's ink, square caps, no corners (`WatchedMarker`).
- **Watch strip:** one 16 px strip of the wash across a still's foot, 6 px in at its sides, the same height in every state so a list of stills keeps one line (`WatchStrip`). In progress, the 12-segment ladder of the minutes watched in rose. Watched through, the watched square then WATCHED in bone label-small, the way a status line says AIRED. Watched and being watched again, the square before the ladder.
- **Aired look:** the still lies under the guide's past shade (the same 20% dark wash as the aired hours) and the title turns muted, as an aired guide cell's does; focus fills the row rose with ink as ever. The episode playing in the strip is now, not past: it keeps the strip but not the shade or the quiet title. On a channel's page the episode on the channel takes the aired look like any other row.
- **Season done:** a season tab whose every episode is watched through carries the watched square in place of its count, in the tab's own ink (muted, bone when open; ink with a rose tick when focused).
- Once started, the facts line gives the time left in place of the length ("12 min left", a started minute counted whole); the still says *watched*, so the line never repeats it.

### Title Logo
A show's or movie's logo from the server stands in for its title in the guide's details, page heroes and both players' banners, fitted into a fixed-height slot aligned bottom-start, the title kept for screen readers. The slot holds its height while the logo loads, and the title returns as text if it fails, so nothing under it moves.

### Stand-in Mark
What stands in for the picture while a channel shows none, wherever the picture would be: the ground (ink in Rose, so the picture reads as dark) with one mark, the same for every reason (`StandInMark`, `RestrictedMark`, `OffAirMark`). An icon, the reason uppercase in the condensed voice, then UNTIL and the end of the dark stretch, uppercase and muted. The reasons are OFF AIR under a TV-off icon, and AGE RESTRICTED or NO ACCESS under a lock; nothing else is said, never the program's rating, the profile's limit or where the program comes from (user decision: a child watching needn't know). Every part is sized from the title, so the mark keeps one shape at every size: the icon at 0.85 of the title, the gap after it 0.3 em, the UNTIL line half the title's size (tracked 0.06 em) 0.25 em under it. Three sizes: full screen in both players, display-medium over 24 px; the guide's preview, on TV and phones alike, headline-small over 12 px; and a line (label-medium, muted, UNTIL following on the same line when it shows) at the top start of a channel tile and in details panels and channel pages. The icon carries the state; the fill alone never does.

- **Off air:** the player and the preview show the mark with UNTIL; the guide's details put the mark in the title's slot (off air has no title) at the preview's size; a channel tile shows it at the top start over a 16-segment ladder of the off-air stretch, the caption saying UNTIL …; a channel with nothing scheduled says NO SCHEDULE the same way, with no ladder. The banner's NOW or NEXT title reads OFF AIR.
- **Restricted:** the program's title, logo and episode line are blurred wherever they appear (banner NOW and NEXT, guide cells, details and actions, tiles, channel page hero), keeping their size so nothing moves; devices without blur (before API 31) show a muted bar in their place. Back-to-back restricted programs are one stretch: one guide cell, one UNTIL. Details panels and channel pages drop the backdrop and overview and put the line mark in the overview's place; a channel tile keeps the blurred logo or title at its foot over the stretch's ladder, the caption saying UNTIL ….
- **Guide cells** for both drop to the ground with a line edge, the icon (12 px on TV, 13 on touch) before the title, and the detail in the condensed field voice, uppercase: an off-air cell reads OFF AIR over UNTIL …, a restricted one its blurred title over AGE RESTRICTED  UNTIL … (or NO ACCESS  UNTIL …), the two-space gap of the status line, never a middle dot.
- **Watch choices** keep menu-row sentence case: *Off air until …*, *Age restricted until …*.

### Profile Tile
A profile on Who's watching: the Jellyfin user's picture on a square (the name's initial in display type when there is none), the name under it, a small lock chip top-right when a PIN is set and OWNER or ADMIN as a deep-rose chip bottom-left. Focus fills the whole tile rose. In *Manage profiles* an edit glyph sits on the picture, and only the profiles the viewer may edit are shown.

### Dashboard
A two-pane console, the Settings menu's own split: the ranks on the ground (52%), the meter on coal (48%) behind a 1 px line, on TV and from 720 px wide; phones stack the meter under the ranks, coal edge to edge, in the same order and layout. On TV both panes fit the screen whole, nothing scrolls (user decision): each pane keeps 20 px top and bottom, and the meter's height is budgeted for its longest readout. No page title while there is something to show; the empty page is the placeholder screen (mark, title, line). Every changing figure is in the condensed cut with tabular figures.
- **Ranks:** compact in-screen tabs (Channels, Movies, Series, Genres) with the span they cover (THIS WEEK, TUE, OCT 7, 9 PM) in muted label-medium at the row's end; on phones the tabs scroll sideways under it when they don't fit. Number one is a backdrop under the wash in a 3 px inset that focus fills rose, like a channel tile: the rose rank block (56 px on TV, 48 on phones, display-small ink figure), the logo (a channel's number block and name in headline-medium, a genre's name), then the time in title-large, a 24-segment ladder of its share and the percentage. Two to five are transparent rows ruled by hairlines: the rank figure in muted headline-small, a 16:9 still (88 px on TV, 72 on phones, coal high while none), the name over a 16-segment ladder, the time in label-large; focus fills the row rose. A movie or series is named by its logo on every rank, not only number one (user decision): 28 px tall on TV, 24 on phones, the title in title-medium where there is none. A channel keeps its number block and name, a genre its name. A new span or ranking cross-fades in over 140 ms, so picking a bar reads as the ranks re-dealing.
- **Meter:** Today and Daily average as two 30-segment ladders on one scale (rose for today, muted for the average), each with its field label left and the time right (headline-medium for today, title-medium for the average) 4 px over its ladder, the OSD's way of setting a level rather than a big-number tile. A hairline, then compact period tabs (Day, Week, Month, Year), then 4 px under them the span between two coal-high step keys (36 px on TV, 44 on touch; one with nowhere to go is muted and focus passes over it) and the span's total in headline-small at the row's end. The meter's groups (the ladders, the hairline, the period, the chart with its key, the habits) stand 12 px apart.
- **Chart:** hand-drawn bars from two arrays, no chart library, 108 px tall on TV (so the meter fits the screen) and 160 on phones. Live TV is rose at the foot of each bar and On demand muted on top, parted by a 2 px sliver so the two read apart without color; a key with both times sits under it. A line hairline marks every tick of the scale (at most eight round steps: every 2 H up to 16 H, whole minutes only up to an hour, figures as 15 MIN or 2 H in muted label-small in a gutter at the end), over a baseline. Marks under the bars in label-small: every six hours, each weekday, every seventh day, each month. Picking a bar dims the rest to 45% and fills its slot one step up; its mark replaces its neighbours'. The focused chart steps up to coal high and the picked slot to line, and the pick's mark fills rose with ink: the D-pad's focus is a fill here too.
- **Habits:** a readout, not tiles, in two columns on TV and phones alike (user decision): related readings share a row (Peak hour and Longest sitting, Current and Best streak, Episodes and Movies finished), each its field label left (label-medium, muted) and its value right (title-small figures), the pair parted by a 1 px line that runs the row's height with 12 px on either side. Rows are 6 px top and bottom, ruled apart by hairlines. A month or year leads with its Daily average across the full width. A label too long for its half takes a second line; the value never gives way.
- The dashboard has no way to turn it off; that is the Watch history page's *Dashboard* row, which asks *Keep history* or *Delete history* in an action dialog.

### What a profile can't change isn't there
A member's Settings has no Servers, Channels or Web control row, and no screen offers *Add to a channel*. The profile page shows its Permissions group (Admin, Parental Rating, Transfer ownership), and each control in it, only to whoever may change them. Nothing is shown locked or dimmed in their place (user decision).

### Profile Page
A settings page titled with the profile's name, in two groups the way account pages group settings: *Profile* (Theme, Authentication, the PIN switch and Change PIN while one is set), the same for everyone who may edit it, then *Permissions* (the Admin switch, the Parental Rating choice, and the Transfer ownership button). Row titles are two words at most and never repeat the profile's name; the title carries it. The *Delete profile* button sits apart at the foot under no heading. Transfer ownership, Delete profile and turning the PIN off each ask first.

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
- **Do** show an age-restricted program as blacked out with its lock, never by hiding the channel or the cell.

### Don't:
- **Don't** use shadows, gradients, glow or blur, or Material's surface tint; not even a fade under a backdrop. The exceptions are an age-restricted program's title and logo, which are blurred, and the player's shades over live video (user decisions; see The Shade Over Picture Rule).
- **Don't** put a panel, strip or block under the player's OSD; it sits on the picture.
- **Don't** float player controls in a panel of their own; they belong in the banner's program column.
- **Don't** add yellow, grey, blue, violet, orange or red, stock Tailwind or shadcn values, or a terracotta-and-cream scheme. The one exception is the fixed danger red on destructive actions (see The Danger Rule).
- **Don't** zoom, scale, glow or ring a focused control; fill it. The text field is the one exception: as a platform field its outline turns rose.
- **Don't** outline a button or key, in any state; it is a fill.
- **Don't** round corners past 4 px or draw pill or circle controls of your own; the platform Material 3 Switch, RadioButton and Checkbox in Settings are the only round controls.
- **Don't** change the guide's layout; restyle only.
- **Don't** move the now line behind the cells.
- **Don't** put a divider, pill indicator, hover or ripple on the navigation tabs.
- **Don't** name the palette's source character anywhere in the UI.
