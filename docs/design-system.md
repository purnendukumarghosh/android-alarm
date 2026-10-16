# Voice Alarm — "Neon Night" design system

A dark, premium, **3D** look: deep navy space lit by neon glows, glass cards
with lit rims and soft shadows, glossy beveled buttons that physically sink
when pressed, glass spheres for knobs and floating buttons. Everything is
drawn in code (no bitmaps except the launcher icon / illustrations), so it is
sharp on every screen and costs almost nothing in APK size.

Framework-only Android views (no AndroidX), Kotlin, minSdk 26, targetSdk 34.

## Tokens (`ui/Neon.kt`, `res/values/colors.xml`)

| Token | Value | Use |
| --- | --- | --- |
| `BG0` / `BG1` / `BG2` | `#05070F` / `#0A0F24` / `#11163A` | backdrop, status/nav bars, dialogs |
| `SURFACE_TOP` → `SURFACE_BOTTOM` | `#1D2354` → `#12163A` | glass card gradient |
| `SURFACE_HI` | `#283070` | hovered/selected glass |
| `SUNKEN` | `#070A1C` | inputs, tracks |
| `CYAN` `GREEN` | `#00E5FF` `#2BFF88` | **AQUA** gradient: primary actions, ON states, FAB |
| `RED` `MAGENTA` | `#FF2E63` `#FF3CAC` | **FLAME** gradient: ringing, dismiss, destructive |
| `BLUE` `VIOLET` | `#3D7BFF` `#8A5CFF` | **ELECTRIC** gradient: secondary accents, selection |
| `AMBER` | `#FFB443` | warnings |
| `TEXT1` / `TEXT2` / `TEXT3` | `#F2F5FF` / `#A3ACD9` / `#67709E` | primary / secondary / muted text |
| `ON_NEON` | `#04101F` | text/icons on AQUA buttons |

Color helpers: `Neon.withAlpha`, `Neon.lighten`, `Neon.darken`, `Neon.blend`.

## Type (`res/values/themes.xml`, font: Manrope)

`Text.Display` (64sp extralight, big times) · `Text.Headline` (26sp extrabold)
· `Text.Title` (18sp bold) · `Text.Body` (15sp medium, TEXT2) · `Text.Caption`
(12sp semibold, TEXT3) · `Text.Overline` (12sp extrabold, tracked caps —
section headers) · `Text.Button` (16sp extrabold).
`TextView.neonText(Neon.AQUA)` paints text with a neon gradient + glow
(use for hero times and the ringing label).

## 3D surfaces (`ui/Depth.kt`)

All are `DepthDrawable`s that reserve space around the body for their own
shadow/glow. **Always apply with `view.depthBackground(drawable)`**, which adds
those insets to the view's padding (so content sits on the body).

| Drawable | Look | Use |
| --- | --- | --- |
| `GlassCardDrawable(ctx, radius, …, outline = Neon.AQUA?, glowColor?, pressable)` | raised glass, lit rim, soft drop shadow; optional neon border/glow; dims when pressed | cards, sections, list rows, dock, sheets, glass buttons |
| `NeonButtonDrawable(ctx, colors = Neon.AQUA/FLAME/ELECTRIC, radius = -1 pill)` | glossy bevel with darker base "thickness" + colored glow; face sinks on press; dims when disabled | primary / destructive buttons |
| `SphereDrawable(ctx, color, color2?, glow)` | glossy ball with specular highlight + contact shadow / glow | FAB, switch knob, slider thumb, selected day bubble, logo |
| `SunkenDrawable(ctx, radius, active, activeColors)` | inset well with inner shadow; neon outline + glow when focused/active | text fields, tracks, segmented-control well |
| `GlowBackgroundDrawable()` | navy gradient + three soft neon glows | root background of every screen |

## Layout rules

- 8dp grid. Screen gutter 20dp. 20dp between sections, 12dp inside.
- Touch targets ≥ 48dp. Primary buttons 56dp tall, pill-shaped, full width in footers.
- Card radius 22dp; chips/pills fully rounded; inputs 16dp.
- Section = overline header (`Text.Overline`) + glass card with content.
- Icons: Material Symbols Rounded, `@drawable/ic_<name>` (outlined) and
  `@drawable/ic_<name>_fill` (filled), white — tint them. Available names:
  menu alarm alarm_add alarm_off add mic stop pause play_arrow record_voice_over
  graphic_eq settings bedtime info help shield chevron_right arrow_back delete
  check vibration snooze repeat label speed bolt close notifications_active
  person edit schedule timer wb_sunny nights_stay volume_up tune history
  waving_hand expand_more more_vert check_circle warning today music_note
  hourglass_top battery_charging_full star favorite language public lock
  dark_mode light_mode home keyboard_arrow_down radio_button_checked
  voice_selection hearing coffee fitness_center work medication school flight
- Selected / ON state = AQUA neon; destructive = FLAME; never more than one
  glowing primary action per screen.
- Motion: 180–260 ms, `DecelerateInterpolator` in / `AccelerateInterpolator` out;
  presses respond instantly (drawable state), knobs/indicators animate.
- Accessibility: every icon-only control has `contentDescription`; custom
  controls announce role/state (`AccessibilityNodeInfo` class name of
  Switch/SeekBar/Button) and support `performClick`; text contrast ≥ 4.5:1.

## App structure

- **MainActivity** — the shell: `NeonDrawerLayout` (hamburger side drawer with
  3D content tilt), `AppBar` (hamburger, title/subtitle, actions), a page
  container and a floating glass `BottomNavBar` footer with a raised center
  "+" FAB. Pages (`ui/Page.kt`): **Alarms**, **Nap** (quick naps), **Voices**
  (voice studio), **Settings**. Intent extras: `EXTRA_START_TAB`
  (`"alarms" | "nap" | "voices" | "settings"`), `EXTRA_OPEN_DRAWER` (Boolean).
- **AlarmEditActivity** — sectioned editor with a sticky footer Save button;
  extra `EXTRA_ID` (Int, -1 = new).
- **AlarmRingActivity** — full-screen ringing screen with an animated 3D clock,
  pulsing neon rings, voice visualizer and slide-to-dismiss; extras
  `AlarmService.EXTRA_ALARM_ID`, `EXTRA_PREVIEW` (Boolean, shows the screen
  without a ringing alarm — used by "Test alarm screen" and screenshots).
