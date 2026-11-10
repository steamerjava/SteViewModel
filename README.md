# ViewModelMOD

A fully client-side Fabric mod for Minecraft **1.21.1** (Java 21) that lets you
edit your first-person ViewModel in-game, without rotating the camera.

## Features

- Independent transforms for **left arm, right arm, left item and right item**
- Position (X/Y/Z), uniform scale and rotation (X/Y/Z), all updated in real time
- Open chat with **T** or **/** and the editor appears automatically
- **Left-click a hand/item** and drag to move or rotate it
- **Middle-click** resets the selected target
- **F6** hides the panel while keeping the transforms active
- **ESC** closes the editor and saves
- Add, delete and switch named profiles
- Arm highlight with configurable **RGB / hex color**, intensity, opacity and an
  on/off toggle
- Numeric entry for every value (click a value box, type, press Enter)
- JSON config with automatic saving/loading in `config/viewmodelmod.json`
- Chat keeps working normally: the editor only consumes input inside its panel
  or when a value box is focused

## Controls

| Input | Action |
| --- | --- |
| **T** / **/** | Open chat and the editor |
| **LMB on hand** | Select that hand and start dragging |
| **LMB drag** | Move/rotate the selected target |
| **RMB on hand** | Switch MOVE / ROTATE |
| **MMB** | Reset the selected target |
| **F6** | Show/hide the editor panel |
| **ESC** | Close the editor and save; press again to close chat |
| **Shift + drag** | Fine adjustment (25 % sensitivity) |

The MOVE plane (XY / XZ / ZY) and MOVE/ROTATE mode can also be changed with the
buttons in the **POSITION** tab. Holding Shift while dragging gives precise
control.

## GUI tabs

- **POSITION** — X, Y, Z, scale and rotation sliders, mode and drag plane
- **GLOW** — highlight toggle, hex color, red/green/blue channels, intensity,
  opacity
- **ACTIONS** — profiles, reset current/all, load defaults, save now and mouse
  sensitivity

Clicking a slider track jumps to that value; scrolling over a slider nudges it.
Clicking a value box opens keyboard entry. Enter commits, ESC cancels.

## Config

The config is stored at `.minecraft/config/viewmodelmod.json` and is written
automatically (debounced) after every change, when the chat screen closes and
when the game stops. Delete the file to start over.

Example:

```json
{
  "version": 1,
  "profiles": [
    {
      "name": "Default",
      "leftHand":  { "x": 0.0, "y": 0.0, "z": 0.0, "scale": 1.0,
                     "rotationX": 0.0, "rotationY": 0.0, "rotationZ": 0.0 },
      "rightHand": { "x": 0.0, "y": 0.0, "z": 0.0, "scale": 1.0,
                     "rotationX": 0.0, "rotationY": 0.0, "rotationZ": 0.0 },
      "leftItem":  { "x": 0.0, "y": 0.0, "z": 0.0, "scale": 1.0,
                     "rotationX": 0.0, "rotationY": 0.0, "rotationZ": 0.0 },
      "rightItem": { "x": 0.0, "y": 0.0, "z": 0.0, "scale": 1.0,
                     "rotationX": 0.0, "rotationY": 0.0, "rotationZ": 0.0 }
    }
  ],
  "activeProfile": 0,
  "highlight": {
    "enabled": true,
    "color": 16777215,
    "intensity": 0.7,
    "opacity": 0.45
  },
  "dragSensitivity": 1.0
}
```

Arm transforms move the complete hand (arm + held item) in view space. Item
transforms are applied to the item only: rotation/scale around the item's own
origin and a view-space offset, so dragging always follows the mouse.

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16+** (built against 0.20.1)
- Fabric API
- Java **21**

The mod is client-only. It does not need to be installed on a server and contains
no server-side logic.

## Building

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat build
```

The jar is produced in `build/libs/`. Use `.\gradlew.bat runClient` to launch a
development client.

## Notes

- The editor is drawn as an overlay on the vanilla `ChatScreen`; it does not
  replace the screen, so chat history, suggestions and typing continue to work.
- Hand picking uses the projected arm cuboids. Item picking projects the baked
  item model's vertices through the item's own first-person display transform, so
  the highlight and click area follow the rendered item instead of a fixed screen
  region.
- The 3D arm glow is drawn with Minecraft's white texture, so it works with any
  skin. Corner brackets mark the selected arm or item.
- The overlay uses scaled GUI coordinates and re-registers its screen callbacks
  on chat init/resize, so it stays correct after changing GUI scale or window
  size.

## License

MIT — see `LICENSE`.
