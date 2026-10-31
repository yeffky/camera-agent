# Preset preview

## ADDED Requirements

### Requirement: Preview presets before capture

The camera screen SHALL let the user preview and switch a local photo preset before taking a photo.

#### Scenario: Select a preset in the viewfinder

- **WHEN** the user selects a different preset or changes its amount on the camera screen
- **THEN** the viewfinder updates to represent the selected preset
- **AND** the currently selected preset and amount are visibly identified.

### Requirement: Captured output uses the selected preset

The photo output SHALL use the selected preset recipe and amount that were active when the shutter was pressed.

#### Scenario: Capture with a selected preset

- **WHEN** the user captures a photo while a preset is selected
- **THEN** the saved processed output uses that preset and amount
- **AND** changing the preview does not alter CameraX analysis data or camera control behavior.

### Requirement: Preset preview degrades honestly

The app SHALL communicate when a device cannot render the live preset preview and SHALL keep ordinary capture and post-capture processing available.

#### Scenario: Live preview effect is unavailable

- **WHEN** the current Android rendering path cannot apply a preset preview
- **THEN** the app shows the ordinary camera preview
- **AND** the preset remains available for post-capture processing.
