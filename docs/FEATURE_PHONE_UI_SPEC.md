# FEATURE_PHONE_UI_SPEC.md

**Version:** 1.0.0  
**Status:** Normative  
**Target:** Non-touch feature phone / keypad phone  
**Reference philosophy:** KaiOS-style interaction + classic Nokia S40-era feature-phone ergonomics  
**Primary reference display:** 240×320 px portrait  
**Input:** D-pad + Center/OK + Left Softkey + Right Softkey + Call + End/Power + numeric keypad  
**Audience:** UI designers, frontend/embedded developers, and AI coding/design agents

---

## 0. PURPOSE

This document is the normative UI/UX specification for a non-touch feature-phone operating system.

When implementing or designing any screen, component, flow, or application:

1. Follow this specification before inventing new behavior.
2. Never assume touch input.
3. Prefer deterministic, low-memory, low-animation interfaces.
4. Optimize for one-handed physical-key operation.
5. Every interactive element must be reachable using hardware keys.
6. Every screen must make the current focus and available actions obvious.
7. Keep interaction patterns consistent across the entire OS.
8. Do not copy Nokia/KaiOS proprietary assets, logos, fonts, or artwork. Use this document as a functional and stylistic reference.

If a requirement is not explicitly specified, choose the simplest behavior consistent with the rules below.

---

# 1. DESIGN PHILOSOPHY

## 1.1 Core principles

The OS SHALL prioritize:

- Predictability
- Speed
- Low cognitive load
- Physical-key efficiency
- Clear focus
- High readability
- Minimal animation
- Consistent navigation
- Immediate feedback
- Graceful error handling

The user should be able to answer these questions at any moment:

- Where am I?
- What is selected?
- What does OK do?
- How do I go back?
- What do the softkeys do?

## 1.2 Interaction hierarchy

Priority order:

1. Hardware key action
2. Focus state
3. Primary action
4. Secondary action
5. Visual decoration

Do not sacrifice navigation clarity for visual decoration.

## 1.3 Feature-phone rule

Do not import smartphone patterns merely because they are familiar.

Avoid:

- Swipe-only interactions
- Gesture-only controls
- Hamburger menus when a simple list works
- Floating action buttons
- Tiny touch targets
- Complex card layouts
- Infinite feeds
- Hidden navigation
- Gesture-dependent dismissal

---

# 2. DEVICE MODEL

## 2.1 Reference hardware

Recommended logical key map:

```text
┌─────────────────────────────┐
│          DISPLAY            │
│          240×320            │
│                             │
├─────────────────────────────┤
│                             │
│                             │
│                             │
├─────────────────────────────┤
│  LSK          OK         RSK │
│               ▲              │
│           ◀   ●   ▶         │
│               ▼              │
├─────────────────────────────┤
│ CALL                    END  │
│                             │
│  1      2 ABC      3 DEF     │
│  4 GHI  5 JKL      6 MNO     │
│  7 PQRS 8 TUV      9 WXYZ    │
│  *      0 +        #         │
└─────────────────────────────┘
```

## 2.2 Logical key names

Use these names in code and design documentation:

```text
KEY_UP
KEY_DOWN
KEY_LEFT
KEY_RIGHT
KEY_CENTER
KEY_LSK
KEY_RSK
KEY_CALL
KEY_END
KEY_BACKSPACE
KEY_0 ... KEY_9
KEY_STAR
KEY_HASH
```

If hardware exposes a dedicated BACK key, map it to `KEY_BACKSPACE` only where appropriate; do not confuse text deletion with navigation.

---

# 3. KEY SEMANTICS

## 3.1 D-pad

### UP
- Move focus to previous item.
- In lists: previous item.
- In grids: item above.
- In text fields: move cursor up where applicable.
- In numeric/value selectors: decrease value when the component defines vertical adjustment.

### DOWN
- Move focus to next item.
- In lists: next item.
- In grids: item below.
- In numeric/value selectors: increase value when defined.

### LEFT
- Move focus left.
- Previous tab.
- Previous value.
- Previous page only when the current component explicitly defines horizontal paging.

### RIGHT
- Move focus right.
- Next tab.
- Next value.
- Next page only when explicitly defined.

### CENTER
- Activate focused item.
- Open selected application.
- Confirm selection.
- Enter edit mode.
- Toggle a focused control when appropriate.

CENTER is the primary activation key.

---

# 4. SOFTKEY SYSTEM

## 4.1 Standard placement

```text
┌─────────────────────────────┐
│           CONTENT           │
│                             │
├─────────────────────────────┤
│ Options     Select      Back │
└─────────────────────────────┘
   LSK         CENTER      RSK
```

## 4.2 Default semantics

### LSK
Contextual action.

Typical values:

- Options
- Menu
- New
- Save
- Add
- Edit
- Send

### CENTER
Primary action.

Typical values:

- Select
- Open
- OK
- Play
- Confirm

The visible center action label is optional if the hardware center key is visually obvious.

### RSK
Navigation action.

Default:

- Back
- Cancel
- Close

If the current screen is the root/home screen, RSK may be blank or context-specific.

## 4.3 Softkey rules

1. Softkeys must always correspond to the current screen.
2. Do not silently change the meaning of a softkey between adjacent screens.
3. RSK should normally navigate backward.
4. Destructive actions must not be the default RSK action.
5. If an action is available through a softkey, it should also be accessible through Options where reasonable.
6. Never require a user to guess a hidden softkey action.

---

# 5. GLOBAL NAVIGATION

## 5.1 Navigation stack

Use a conventional stack:

```text
Home
  ↓
App Launcher
  ↓
Settings
  ↓
Display
```

BACK:

```text
Display → Settings → App Launcher → Home
```

Do not jump directly to Home unless the user presses END or the application explicitly exits.

## 5.2 END key

Default behavior:

- From an application: exit to Home.
- During an active call: end call.
- During an alarm: dismiss/snooze according to alarm state.
- During text entry: do not silently discard unsaved content unless explicitly defined.
- During a critical confirmation dialog: follow the dialog's cancel behavior.

Long press may power off or open the power menu, depending on hardware policy.

## 5.3 CALL key

Default:

- Idle/Home: open call history or dialer depending on product configuration.
- Contact: call selected contact.
- Dialer: initiate call.
- Incoming call: answer.
- Active call: optional secondary call behavior if supported.

---

# 6. FOCUS SYSTEM

## 6.1 Fundamental rule

Every interactive screen SHALL have exactly one logical focused element at a time.

Exceptions:

- Text input with cursor
- Composite controls with an internal cursor
- Purely informational screens

## 6.2 Focus states

Every component should conceptually support:

```text
NORMAL
FOCUSED
PRESSED
SELECTED
DISABLED
ERROR
```

## 6.3 Focus visibility

Focused elements must be visually obvious.

Preferred methods:

- Solid highlight background
- Inverted foreground/background
- Clear outline
- Strong contrast
- Optional subtle animation

Do not rely only on a small color change.

## 6.4 Focus persistence

When returning to a previous screen:

- Restore the previous focus when practical.
- For long lists, restore previous scroll position.
- For settings, restore the previously selected row.
- For app launchers, restore the previous icon.

## 6.5 Focus movement

For vertical lists:

```text
UP   → previous item
DOWN → next item
```

For grids:

```text
LEFT  → previous column
RIGHT → next column
UP    → row above
DOWN  → row below
```

Do not skip items unexpectedly.

If the target cell does not exist:

- Move to the nearest valid cell in the intended direction.
- Do not wrap unless the component explicitly enables wrapping.

Default: no wrap.

---

# 7. GRID NAVIGATION

## 7.1 App grid

Default layout for 240×320:

- 3 columns
- 3 or 4 visible rows depending on header/softkey usage

Recommended:

```text
┌─────────────────────────────┐
│ Menu                        │
├─────────────────────────────┤
│  ☎       ✉       👤        │
│ Phone   Msg    Contacts      │
│                             │
│  ▣       ♪       ⚙         │
│ Camera  Music   Settings     │
│                             │
│  ◷       ⏰       ▤         │
│ Calendar Alarm   Files       │
├─────────────────────────────┤
│ Options          Select      │
└─────────────────────────────┘
```

Actual icons are implementation-specific.

## 7.2 Grid focus

The focus is on one grid cell, not merely on its icon.

Focus should include:

- Icon
- Label
- Optional background highlight

## 7.3 Numeric shortcuts

Optional product feature:

```text
1–9 → directly launch corresponding grid item
```

If enabled, show a consistent numeric mapping.

Do not use numeric shortcuts in a way that conflicts with text input.

---

# 8. SCREEN LAYOUT

## 8.1 Reference viewport

```text
WIDTH  = 240 px
HEIGHT = 320 px
```

## 8.2 Vertical regions

Default:

```text
┌─────────────────────────────┐
│ Status Bar        0–24 px   │
├─────────────────────────────┤
│ Header             24–56 px │
├─────────────────────────────┤
│                             │
│ Content            56–288 px│
│                             │
├─────────────────────────────┤
│ Softkey Bar       288–320 px│
└─────────────────────────────┘
```

These dimensions are a baseline, not an absolute requirement.

## 8.3 Content rule

Content must never be hidden behind the softkey bar.

Scrollable content must reserve space for:

- Header
- Status bar if present
- Softkey bar

---

# 9. STATUS BAR

## 9.1 Recommended indicators

From left to right:

```text
Signal
Network/SIM
Wi-Fi
Bluetooth
Battery
Time
```

Not every indicator must be visible simultaneously.

## 9.2 Status-bar behavior

- Keep iconography compact.
- Use consistent icon positions.
- Avoid animated indicators unless necessary.
- Critical state changes may temporarily appear as a notification.

---

# 10. HEADER

## 10.1 Header contents

Typical:

```text
[Back]     Screen Title       [Context]
```

On a traditional feature phone, back should normally remain assigned to RSK rather than appearing as a touch-like button.

## 10.2 Title rules

- Short.
- Concrete.
- Prefer one line.
- Truncate with ellipsis if necessary.
- Do not horizontally scroll titles unless unavoidable.

Examples:

```text
Messages
Contacts
Settings
Display
Sound
Network
```

---

# 11. TYPOGRAPHY

## 11.1 Font requirements

Use a highly legible sans-serif system font.

Do not bundle or distribute proprietary Nokia fonts unless legally licensed.

## 11.2 Reference sizes

> 📌 **KeydroidX Implementation Note:** In the KeydroidX ecosystem, typography is standardized via `@dimen/nokia_font_*` tokens defined in `keydroidx-core/docs/11-typography-and-font-spec.md`. Always use the semantic tokens:
> - Display: `@dimen/nokia_font_display` (16sp)
> - Title: `@dimen/nokia_font_title` (13sp)
> - Body: `@dimen/nokia_font_body` (12sp)
> - Small Title: `@dimen/nokia_font_small_title` (11sp)
> - Caption: `@dimen/nokia_font_caption` (9sp)
> - Micro: `@dimen/nokia_font_micro` (7sp)

For a 240×320 display:

```text
Screen title:       16–18 px
Primary list text:  14–16 px
Secondary text:     11–13 px
Softkey text:       12–14 px
Status text:        10–12 px
Large numeric text: 24–36 px
```

## 11.3 Typography rules

- Use sentence case by default.
- Avoid excessive bold.
- Keep line height comfortable.
- Never use ultra-light text.
- Numeric information should use tabular-looking alignment where practical.

---

# 12. COLOR SYSTEM

The visual language should evoke classic feature-phone simplicity without copying proprietary themes.

## 12.1 Base palette

Recommended semantic roles:

```text
BACKGROUND
SURFACE
TEXT_PRIMARY
TEXT_SECONDARY
TEXT_DISABLED
FOCUS
FOCUS_TEXT
ACCENT
SUCCESS
WARNING
ERROR
DIVIDER
```

Example baseline:

```text
Background:     #F5F7F9
Surface:        #FFFFFF
Text Primary:   #111827
Text Secondary: #5B6470
Text Disabled:  #9AA3AD
Focus:          #1E5AA8
Focus Text:     #FFFFFF
Accent:         #1E5AA8
Success:        #2E7D32
Warning:        #A15C00
Error:          #B3261E
Divider:        #D7DCE1
```

These are starting values, not mandatory brand colors.

## 12.2 Contrast

Text and focus states must remain readable in:

- Bright daylight
- Low brightness
- Low-quality LCD
- Monochrome or reduced-color themes if supported

---

# 13. ICONOGRAPHY

## 13.1 Style

Use:

- Simple silhouettes
- Consistent stroke weight
- Small number of internal details
- Strong recognizability at 16–24 px
- Minimal gradients
- No unnecessary shadows

## 13.2 Icon grid

Recommended source grid:

```text
24×24 px
```

Optional small grid:

```text
16×16 px
```

## 13.3 Icon states

Provide at least:

```text
normal
focused
disabled
active
```

Do not encode meaning by color alone.

---

# 14. LIST COMPONENT

## 14.1 Standard list

```text
┌─────────────────────────────┐
│ Settings                    │
├─────────────────────────────┤
│ > Display                   │
│   Sound                     │
│   Network                   │
│   Security                  │
│   Storage                   │
│   Language                  │
├─────────────────────────────┤
│ Options              Back   │
└─────────────────────────────┘
```

## 14.2 Row height

Recommended:

```text
Single-line: 40–48 px
Two-line:    52–64 px
```

Adjust according to font and target hardware.

## 14.3 List behavior

- One focus item.
- UP/DOWN moves exactly one item.
- Page scrolling should preserve focus.
- Long lists may support accelerated scrolling on long press.
- The scrollbar should be subtle.

## 14.4 Two-line list

Structure:

```text
Primary title
Secondary information
```

Example:

```text
John Smith
+65 8123 4567
```

Primary text must remain visually dominant.

---

# 15. OPTION MENU

Option menus are modal menus containing contextual actions.

Example:

```text
┌─────────────────────────────┐
│ Options                     │
├─────────────────────────────┤
│ Open                        │
│ Edit                        │
│ Delete                      │
│ Share                       │
│ Details                     │
├─────────────────────────────┤
│ Select                Back  │
└─────────────────────────────┘
```

Rules:

1. Open from LSK or an explicit menu action.
2. Focus the first safe/default action.
3. CENTER activates.
4. RSK closes.
5. BACK closes.
6. Destructive actions should not be preselected by default.

---

# 16. DIALOG

## 16.1 Confirmation dialog

```text
┌─────────────────────────────┐
│ Delete message?             │
│                             │
│ This action cannot be       │
│ undone.                     │
│                             │
│       Cancel     Delete     │
└─────────────────────────────┘
```

## 16.2 Dialog rules

- Modal.
- Focus must be inside the dialog.
- Default focus should be the safer option.
- BACK/RSK normally means Cancel.
- Destructive action must require explicit confirmation.
- Do not stack unnecessary dialogs.

---

# 17. TOAST / TEMPORARY NOTICE

Use for non-blocking feedback.

Examples:

```text
Message sent
Saved
Bluetooth enabled
Copied
```

Rules:

- Short duration.
- Does not steal focus.
- Does not require interaction.
- Must not hide critical information.

Typical duration:

```text
1.5–3 seconds
```

---

# 18. NOTIFICATION SYSTEM

## 18.1 Notification priorities

```text
INFO
NOTICE
WARNING
CRITICAL
```

## 18.2 Notification behavior

A notification may:

- Appear as an icon.
- Appear as a temporary notice.
- Open a notification list.
- Interrupt only when user action is required.

Examples of interruptive events:

- Incoming call
- Alarm
- SIM/security error
- Critical battery condition

---

# 19. TEXT INPUT

## 19.1 Modes

Support as product requirements allow:

```text
ABC
abc
123
Predictive/T9
Symbols
```

## 19.2 Multi-tap

Reference:

```text
2 → A
22 → B
222 → C
2222 → 2
```

Use timeout or RIGHT to commit the current character.

## 19.3 Cursor

- LEFT/RIGHT moves cursor.
- BACKSPACE deletes previous character.
- Long press BACKSPACE may delete continuously.
- UP/DOWN may move between lines.
- CENTER may confirm when not editing.

## 19.4 Numeric input

For numeric-only fields:

- 0–9 enter digits.
- BACKSPACE deletes.
- LEFT/RIGHT moves cursor where editing is supported.
- Do not invoke alphabetic input.

---

# 20. PREDICTIVE TEXT / T9

The exact dictionary implementation is product-specific.

UI requirements:

```text
┌─────────────────────────────┐
│ Message                     │
├─────────────────────────────┤
│ Hello worl_                 │
│                             │
│ world  word  work           │
├─────────────────────────────┤
│ Options              Send   │
└─────────────────────────────┘
```

Rules:

- Candidate list must be keyboard reachable.
- Candidate selection must not trap the user.
- Clear distinction between typed text and candidate text.
- Provide a predictable way to switch between predictive and multi-tap modes.

---

# 21. HOME SCREEN

## 21.1 Default structure

```text
┌─────────────────────────────┐
│  ▮▮▮       100%       12:30 │
│                             │
│                             │
│          12:30              │
│       Monday, 24 Aug        │
│                             │
│      Network Name           │
│                             │
│                             │
│                             │
├─────────────────────────────┤
│ Menu                 Names  │
└─────────────────────────────┘
```

## 21.2 Home behavior

CENTER:

- Open configured primary launcher/action.

LSK:

- Menu / launcher.

RSK:

- Contacts or configured shortcut.

UP/DOWN/LEFT/RIGHT:

- Optional shortcut system, but do not make essential functionality inaccessible elsewhere.

---

# 22. APP LAUNCHER

Recommended:

- 3×3 grid.
- Icon + label.
- Clear focus.
- Stable ordering.
- Optional folders only if necessary.

Avoid excessive nesting.

---

# 23. PHONE / DIALER

## 23.1 Dialer

```text
┌─────────────────────────────┐
│ Dial                        │
├─────────────────────────────┤
│                             │
│       +65 8123 4567         │
│                             │
│       John Smith            │
│                             │
├─────────────────────────────┤
│ Options               Call  │
└─────────────────────────────┘
```

Digits are entered directly from the keypad.

CALL starts the call.

## 23.2 Incoming call

Highest-priority interaction.

```text
┌─────────────────────────────┐
│        Incoming call        │
│                             │
│         John Smith          │
│        +65 8123 4567        │
│                             │
├─────────────────────────────┤
│ Silent               Answer │
└─────────────────────────────┘
```

CALL = Answer  
END = Reject

---

# 24. CALL HISTORY

Categories may include:

```text
All
Missed
Received
Dialed
```

List row:

```text
John Smith
Today 14:32
Mobile
```

CALL from a selected row initiates a call.

---

# 25. CONTACTS

## 25.1 Contact list

Primary sort:

```text
A–Z
```

Optional:

```text
Favorites
Groups
Recent
```

## 25.2 Contact detail

```text
John Smith

Mobile
+65 8123 4567

Home
+65 6123 4567

Email
john@example.com
```

Primary action:

CALL

Secondary actions:

- Message
- Edit
- Delete
- Share

---

# 26. MESSAGES

## 26.1 Conversation list

Recommended:

```text
John Smith
See you at 8pm
Today 17:02

Alice
Photo received
Today 15:41
```

## 26.2 Conversation

```text
┌─────────────────────────────┐
│ John Smith                  │
├─────────────────────────────┤
│ Hi                          │
│                             │
│ See you at 8pm              │
│                             │
│ OK                          │
├─────────────────────────────┤
│ Options               Reply │
└─────────────────────────────┘
```

REPLY should enter text input.

---

# 27. CAMERA

The camera UI must remain simple.

Typical actions:

```text
CENTER → capture
LEFT/RIGHT → optional mode
UP/DOWN → optional zoom
RSK → gallery/back
```

Do not require touch gestures.

---

# 28. GALLERY

Use a simple grid or list.

Default:

- Thumbnail grid
- Focused item
- CENTER opens
- RSK returns

Do not make pinch/zoom mandatory.

---

# 29. MUSIC PLAYER

Required information:

```text
Track title
Artist
Album
Playback position
Playback state
```

Recommended keys:

```text
CENTER → Play/Pause
LEFT   → Previous
RIGHT  → Next
UP     → Volume Up
DOWN   → Volume Down
```

If volume keys exist, prefer dedicated volume keys.

---

# 30. CALENDAR

Views:

```text
Month
Week
Day
Agenda
```

For a small display, default to Month or Agenda.

Navigation:

```text
LEFT/RIGHT → previous/next period
UP/DOWN    → focus date/event
CENTER     → open date/event
```

---

# 31. ALARM / CLOCK

Alarm list:

```text
07:00  Weekdays   ON
08:30  Saturday   OFF
09:00  Daily      ON
```

CENTER edits.

LSK may create a new alarm.

RSK returns.

---

# 32. CALCULATOR

Use direct numeric keypad entry.

```text
  123.45

  +  −  ×  ÷
  =  C  DEL
```

Do not require focus movement for every digit.

---

# 33. SETTINGS

Recommended hierarchy:

```text
Settings
├── Network
├── SIM
├── Bluetooth
├── Wi-Fi
├── Display
├── Sound
├── Notifications
├── Security
├── Storage
├── Language
├── Date & Time
├── Accessibility
└── About phone
```

Keep nesting shallow.

Prefer:

```text
Settings
  → Display
      → Brightness
```

Avoid:

```text
Settings
  → System
      → Device
          → Display
              → Screen
                  → Brightness
```

---

# 34. SETTINGS CONTROLS

## 34.1 Boolean

Use:

```text
Bluetooth
[ ON ]
```

CENTER toggles.

## 34.2 Choice

```text
Language
English >
```

CENTER opens a selection list.

## 34.3 Slider

For brightness/volume:

```text
Brightness

[████████░░░░]

Low          High
```

LEFT/RIGHT changes value.

## 34.4 Radio selection

```text
Theme

● Classic
○ Dark
○ High contrast
```

CENTER selects.

---

# 35. ACCESSIBILITY

Minimum requirements:

- High contrast mode.
- Large text option where screen space permits.
- Reduced animation.
- Clear focus.
- Do not rely solely on color.
- Adjustable ringtone/notification volume.
- Optional vibration.
- Consistent key behavior.

Accessibility options must themselves be reachable using hardware keys.

---

# 36. ANIMATION

Animation is optional and subordinate to responsiveness.

Recommended:

```text
Focus transition:      80–150 ms
Dialog appearance:     100–180 ms
Page transition:       120–200 ms
Toast:                 fade 100–150 ms
```

Avoid:

- Long easing curves.
- Decorative transitions.
- Full-screen parallax.
- Animations that delay input.

If animation performance is poor, remove animation rather than slowing navigation.

---

# 37. SOUND AND HAPTIC

Every important physical-key action should have optional feedback:

- Key click
- Focus movement
- Selection
- Error
- Notification
- Incoming call
- Alarm

Allow the user to disable key sounds.

Vibration should be short and distinct.

---

# 38. ERROR HANDLING

Errors must be:

- Specific
- Short
- Actionable

Bad:

```text
Error 0x0004A7
```

Good:

```text
Unable to send message.
Check your network connection.
```

Provide a clear next action.

Typical actions:

```text
OK
Retry
Cancel
Settings
```

---

# 39. EMPTY STATES

Every empty list must explain itself.

Examples:

```text
No contacts

Add a contact to see it here.
```

```text
No messages

Your conversations will appear here.
```

Do not show a blank screen.

---

# 40. LOADING STATES

Prefer lightweight indicators:

```text
Loading...
```

or

```text
Searching...
```

For short operations, a spinner is optional.

Do not block the entire UI unnecessarily.

---

# 41. CONFIRMATION RULES

Confirmation is REQUIRED for:

- Permanent deletion
- Factory reset
- SIM/security changes with consequences
- Account removal
- Formatting storage
- Other irreversible operations

Confirmation is NOT required for:

- Opening an app
- Changing a reversible setting
- Moving focus
- Playing media
- Saving ordinary settings when save is explicit

---

# 42. DESTRUCTIVE ACTIONS

Use explicit labels:

Good:

```text
Delete
Remove
Erase
Reset
```

Avoid:

```text
OK
Do it
Continue
```

for destructive operations.

The destructive action should not be the default focused action unless there is a strong product-specific reason.

---

# 43. LONG PRESS

Long press is allowed only where it adds clear value.

Recommended uses:

```text
0 long press → +
1 long press → voicemail shortcut if configured
BACKSPACE long press → continuous deletion
END long press → power menu
number key long press → speed dial if configured
```

Long press must never be the only way to discover a critical function.

---

# 44. SHORT PRESS VS LONG PRESS

Every key should have deterministic timing.

Suggested:

```text
Short press: < 500 ms
Long press:  ≥ 600 ms
Repeat start: ~500 ms
Repeat interval: ~100 ms
```

Actual thresholds may be tuned for hardware.

---

# 45. SCROLLING

Scrolling must follow focus.

Rule:

> The focus moves first; the viewport follows the focus.

Do not scroll content independently while focus remains ambiguous.

For long lists:

```text
UP/DOWN → one item
Long UP/DOWN → accelerated scrolling
```

---

# 46. SEARCH

Search fields should support numeric keypad text entry.

Behavior:

- Enter search mode explicitly.
- Show cursor.
- Update results progressively when practical.
- Keep focus on the search field until the user moves into results.
- RSK/BACK exits search.

---

# 47. GLOBAL BACK BEHAVIOR

Priority:

1. Close modal dialog.
2. Close option menu.
3. Exit text-edit mode if applicable.
4. Navigate to previous screen.
5. If at root, remain on Home or use product-specific behavior.

Never make BACK unexpectedly delete data.

---

# 48. DATA LOSS PROTECTION

If the user has unsaved edits:

```text
Back
  ↓
Unsaved changes?
  ↓
Save / Discard / Cancel
```

If no data has changed:

```text
Back → previous screen
```

---

# 49. PERFORMANCE RULES

The UI should feel instantaneous.

Target:

```text
Key-to-focus feedback: < 50 ms when possible
Screen transition:      < 200 ms when possible
App launch:             show feedback immediately
```

Avoid expensive visual effects.

Prefer:

- Static layouts
- Cached icons
- Simple transitions
- Small images
- Predictable rendering

---

# 50. OFFLINE-FIRST BEHAVIOR

Core phone functions should remain usable without network access:

- Dialer
- Contacts
- Call history
- Messages stored locally
- Calculator
- Alarm
- Calendar
- Settings
- Gallery
- Music

Network-dependent screens must clearly communicate offline state.

---

# 51. LOCALIZATION

The UI must support text expansion.

Do not hard-code layouts assuming English lengths.

Prepare for:

- English
- Chinese
- Japanese
- Korean
- German
- French
- Spanish
- Portuguese
- Arabic if RTL support is required

If RTL is supported:

- Mirror navigation layout where appropriate.
- Do not blindly reverse numeric keypad layouts.

---

# 52. DATE / TIME

Use system locale.

Examples:

```text
24 Aug 2026
24/08/2026
Aug 24, 2026
```

Do not hard-code date format into individual apps.

---

# 53. SYSTEM-WIDE COMPONENT CONTRACT

Every component SHOULD expose:

```text
id
label
enabled
visible
focused
selected
disabled
onFocus
onActivate
onBack
```

Interactive components should have predictable keyboard behavior.

---

# 54. SCREEN CONTRACT

Every screen SHOULD define:

```text
screenId
title
initialFocus
focusOrder
softkeys
backBehavior
keyHandlers
scrollBehavior
state
emptyState
loadingState
errorState
```

Example:

```text
screenId: settings.display
title: Display
initialFocus: brightness
softkeys:
  LSK: Options
  CENTER: Select
  RSK: Back
backBehavior: navigatePrevious
```

---

# 55. APP CONTRACT

Each application SHOULD define:

```text
appId
name
icon
entryScreen
requiredPermissions
navigationRoot
backgroundBehavior
notificationBehavior
storage
```

---

# 56. AI DESIGN RULES

When an AI agent generates a new screen:

1. Use the 240×320 reference viewport unless another viewport is specified.
2. Assume non-touch hardware.
3. Use D-pad navigation.
4. Define exactly one initial focus.
5. Define LSK, CENTER, and RSK behavior.
6. Define BACK behavior.
7. Define every interactive element's key path.
8. Use the existing components before inventing new components.
9. Keep information density appropriate for a feature phone.
10. Avoid smartphone-only interaction patterns.
11. Do not introduce hidden gestures.
12. Do not create a new visual language for one screen.
13. Reuse typography and semantic colors.
14. Reuse focus styling.
15. Reuse list/grid/dialog patterns.
16. Include loading, empty, and error states when relevant.
17. Consider localization.
18. Consider long labels.
19. Consider long lists.
20. Consider unsaved data.
21. Make destructive operations explicit.
22. Keep animation short or omit it.
23. Prefer keyboard efficiency over decorative UI.
24. Do not assume network availability.
25. Do not copy proprietary Nokia/KaiOS assets.

---

# 57. AI IMPLEMENTATION OUTPUT RULES

When asked to implement a screen, the AI SHOULD return:

```text
1. Screen description
2. Component hierarchy
3. Focus order
4. Key mapping
5. Softkey mapping
6. State model
7. UI implementation
8. Empty/loading/error states
9. Accessibility considerations
10. Test cases
```

When asked to implement code:

- Keep components reusable.
- Keep navigation separate from presentation.
- Do not hard-code navigation logic into individual visual elements when a navigation controller can own it.
- Do not duplicate focus logic.
- Do not duplicate softkey logic.
- Use semantic names rather than screen-specific magic numbers.

---

# 58. AI REVIEW CHECKLIST

Before considering a screen complete, verify:

```text
[ ] No touchscreen interaction is required.
[ ] Every interactive item is D-pad reachable.
[ ] Exactly one item is focused.
[ ] Focus is visually obvious.
[ ] CENTER has a deterministic action.
[ ] LSK has a deterministic action.
[ ] RSK has a deterministic action.
[ ] BACK behavior is defined.
[ ] END behavior is safe.
[ ] Long lists scroll correctly.
[ ] Focus does not disappear during scrolling.
[ ] Empty state exists where needed.
[ ] Loading state exists where needed.
[ ] Error state exists where needed.
[ ] Destructive actions require confirmation.
[ ] Text remains readable at target resolution.
[ ] Labels do not overlap.
[ ] Localization will not break the layout.
[ ] No unnecessary animation is used.
[ ] No proprietary assets have been copied.
[ ] The screen uses existing design-system components.
```

---

# 59. RECOMMENDED DEFAULT COMPONENT SET

The first implementation should include:

```text
StatusBar
Header
SoftkeyBar
List
ListItem
Grid
GridItem
TabBar
TextInput
NumberInput
SearchInput
Checkbox
Radio
Switch
Slider
Progress
Dialog
OptionMenu
Toast
Notification
DatePicker
TimePicker
ValueSelector
ScrollIndicator
Icon
```

Do not implement all components at once. Build the core navigation primitives first.

---

# 60. IMPLEMENTATION PRIORITY

Recommended order:

```text
P0
├── Input/key event system
├── Focus manager
├── Navigation stack
├── Screen container
├── Softkey manager
├── List
├── Dialog
└── OptionMenu

P1
├── Grid
├── TextInput
├── NumberInput
├── Toast
├── Notification
├── Settings controls
└── App launcher

P2
├── T9
├── Contacts
├── Messages
├── Dialer
├── Call history
└── System settings

P3
├── Camera
├── Gallery
├── Music
├── Calendar
├── Alarm
└── File manager
```

---

# 61. REFERENCE SCREEN TEMPLATE

Use this template whenever documenting a screen:

```text
SCREEN ID:
SCREEN NAME:

PURPOSE:

VIEWPORT:
240×320

HEADER:
...

CONTENT:
...

INITIAL FOCUS:
...

FOCUS ORDER:
1.
2.
3.

KEY MAP:
UP:
DOWN:
LEFT:
RIGHT:
CENTER:
LSK:
RSK:
BACK:
END:

SOFTKEYS:
LSK:
CENTER:
RSK:

STATES:
Normal:
Loading:
Empty:
Error:
Disabled:

NAVIGATION:
Enter:
Exit:
Back:

ACCESSIBILITY:
...

LOCALIZATION:
...

NOTES:
...
```

---

# 62. REFERENCE COMPONENT TEMPLATE

```text
COMPONENT ID:
COMPONENT NAME:

PURPOSE:

VISUAL STRUCTURE:

STATES:
Normal:
Focused:
Pressed:
Selected:
Disabled:
Error:

KEYBOARD:
UP:
DOWN:
LEFT:
RIGHT:
CENTER:
BACK:

SOFTKEYS:

FOCUS BEHAVIOR:

SCROLL BEHAVIOR:

ACCESSIBILITY:

LOCALIZATION:

ERROR BEHAVIOR:
```

---

# 63. DESIGN DECISION RULE

When two possible designs are both valid, choose the one that:

1. Requires fewer key presses.
2. Requires less memorization.
3. Has fewer hidden states.
4. Has clearer focus.
5. Has fewer navigation levels.
6. Has fewer animations.
7. Has better readability.
8. Behaves consistently with existing screens.

The OS should feel like one system, not a collection of independent apps.

---

# 64. FINAL NORMATIVE RULES

These rules override aesthetic preferences:

```text
RULE 01
Physical-key navigation is mandatory.

RULE 02
No essential feature may require touch.

RULE 03
Every interactive screen has a clear focus.

RULE 04
CENTER activates the focused element unless explicitly defined otherwise.

RULE 05
RSK normally means Back/Cancel.

RULE 06
LSK normally represents the contextual action.

RULE 07
BACK closes the current modal/context before navigating backward.

RULE 08
END returns to Home or performs its current high-priority hardware action.

RULE 09
Focus follows the user's navigation direction predictably.

RULE 10
The viewport follows focus.

RULE 11
Destructive actions require explicit confirmation.

RULE 12
No important state may be communicated by color alone.

RULE 13
Animations must never delay essential input.

RULE 14
Empty, loading, and error states must be intentionally designed.

RULE 15
All apps must use the system navigation and component conventions.

RULE 16
Do not invent a new interaction pattern when an existing pattern applies.

RULE 17
The simplest predictable interaction is preferred.

RULE 18
The system must remain usable under poor network conditions.

RULE 19
The system must be designed for localization.

RULE 20
Do not copy proprietary Nokia/KaiOS assets; reproduce the functional principles, not protected brand assets.
```

---

# 65. AI MASTER PROMPT

The following prompt may be appended to an AI coding/design session:

```text
You are designing and implementing a non-touch feature-phone operating system.

FEATURE_PHONE_UI_SPEC.md is the normative UI/UX specification.

Treat this specification as the system design authority.

The device uses:
- 240×320 primary display
- D-pad
- Center/OK
- Left Softkey
- Right Softkey
- Call
- End/Power
- Numeric keypad

The UI philosophy combines:
- classic feature-phone ergonomics
- KaiOS-style deterministic hardware-key navigation
- classic Nokia S40-era simplicity and information density

Do not assume a touchscreen.

Before generating any screen:
1. Determine the screen's purpose.
2. Determine its navigation hierarchy.
3. Define initial focus.
4. Define focus order.
5. Define UP/DOWN/LEFT/RIGHT behavior.
6. Define CENTER behavior.
7. Define LSK behavior.
8. Define RSK behavior.
9. Define BACK behavior.
10. Define END behavior if relevant.
11. Define loading/empty/error states.
12. Reuse existing components.
13. Check localization.
14. Check accessibility.
15. Check that no interaction requires touch.

Do not invent smartphone UI patterns unless explicitly requested.

When uncertain, choose the simpler behavior with fewer key presses and fewer hidden states.

The final result must feel like one coherent feature-phone operating system.
```

---

# 66. VERSIONING

Use semantic versioning:

```text
MAJOR.MINOR.PATCH
```

Examples:

```text
1.0.0
1.1.0
1.1.1
2.0.0
```

Change policy:

```text
MAJOR → breaking navigation/design behavior
MINOR → new components or capabilities
PATCH → corrections without changing interaction contracts
```

---

# 67. CHANGELOG

## 1.0.0

Initial normative specification.

Defined:

- Device model
- Hardware keys
- D-pad navigation
- Focus system
- Softkey system
- Screen layout
- Typography
- Colors
- Icons
- Lists
- Grids
- Dialogs
- Option menus
- Input
- T9 principles
- Notifications
- Core system apps
- Settings
- Accessibility
- Animation
- Sound/haptic
- Error handling
- AI implementation rules
- AI review checklist
- Component and screen contracts

