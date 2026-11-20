# iKeyboard

An Android keyboard (IME) that looks and behaves like the stock iPhone keyboard. It's written in Kotlin
and draws everything itself on a Canvas, with no AndroidX or third-party runtime libraries.

## Features

- **iOS look**: key sizes, spacing, corner radii, the darker function keys and the 1dp key shadows.
  It switches between light and dark with the system, or you can force either one.
- **Key preview balloons** that rise out of the key you press, including over the suggestion bar.
- **Shift**: tap for one capital, double-tap for caps lock (the arrow gets an underline). You can hold
  shift while typing, or slide from shift onto a letter.
- **Auto-capitalization** at the start of sentences, plus a standalone "i" becomes "I".
- **Accents and alternates**: touch and hold a key (e → è é ê ë ē ė ę, $ → ₽ ¥ € ¢ £ ₩, . → …, and so on),
  then slide to pick one.
- **123 / #+= pages** with the iOS layouts. Slide from `123` to a symbol and let go to type it and jump
  back to letters. A space typed on the number page also returns to letters.
- **QuickType bar**: the left slot shows your word in quotes (tap it to keep that exact word), the other
  two show completions.
- **Auto-correction** that knows the keyboard layout ("teh" → "the", "dont" → "don't", "im" → "I'm").
  The correction is applied when you type a space or punctuation. Press delete right after a correction
  to undo it, and the keyboard learns that word.
- **"." shortcut**: double-tap space to insert ". ".
- **Space-bar trackpad**: touch and hold space, then drag to move the cursor.
- **Delete** repeats while held and switches to deleting whole words after about two seconds. It also
  removes a whole emoji (or other grapheme cluster) at a time.
- **Return key** reads `go`, `search`, `send`, `done` or `next` to match the text field, and turns blue
  where iOS makes it blue.
- **Layouts that adapt to the field**: email fields get `@` and `.` keys, URL fields get `.` `/` `.com`
  (hold `.com` for .net/.org…), and number and phone fields get the iOS number pad.
- **Emoji panel** that scrolls sideways, with sticky section titles, a category bar and a
  "Frequently Used" section.
- Optional key-click sound (muted when the phone is on silent or vibrate) and optional haptics.
- Touch and hold the emoji key to switch to a different keyboard.

## Build

You need JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # dictionary / auto-correct tests
```

## Install and enable

1. `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to your phone.
2. Open **iKeyboard** from the launcher and tap **Enable keyboard**. Turn on iKeyboard in system settings.
3. Tap **Switch to iKeyboard** and pick it.
4. Use the "Try it" box on the same screen, then adjust the settings to taste.

## Project layout

| File | What it does |
| --- | --- |
| `IKeyboardService.kt` | `InputMethodService`: talks to the text field, handles auto-correct, suggestions, the "." shortcut, return actions and feedback |
| `KeyboardView.kt` | Draws the keys, suggestion strip, balloons and pop-ups; handles multi-touch, shift, sliding and the trackpad |
| `Layouts.kt` | Letter, number, symbol and pad layouts, plus the accent lists |
| `Dictionary.kt` | Word frequencies, completions, and a keyboard-aware Damerau–Levenshtein auto-correct |
| `EmojiView.kt`, `EmojiData.kt` | Emoji picker |
| `SettingsActivity.kt` | Setup and settings screen styled like iOS Settings |

## Credits

The English word list (`app/src/main/assets/words.txt`) comes from
[wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer and is licensed CC BY-SA 4.0.

This project is not affiliated with Apple. It doesn't contain any Apple fonts, artwork or code.
