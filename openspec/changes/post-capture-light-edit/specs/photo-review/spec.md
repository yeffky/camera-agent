# Photo review

## ADDED Requirements

### Requirement: Adjust preset strength before saving

The result preview SHALL let the user adjust the selected preset strength from 0 to 100 and view the updated result before saving.

#### Scenario: User adjusts strength

- **WHEN** the user moves the strength slider
- **THEN** the app regenerates the preview from the captured source using the latest selected amount
- **AND** shows the current numeric strength.

### Requirement: Save the previewed strength

The app SHALL save the same preset amount currently shown in the preview and SHALL persist that amount only after a successful save.

#### Scenario: Save after editing

- **WHEN** the user saves a successfully generated result
- **THEN** the saved photo uses the displayed preset amount
- **AND** the preset store remembers that amount for the next capture.

#### Scenario: Leave or retake without saving

- **WHEN** the user dismisses the preview or chooses to retake
- **THEN** the preset store keeps its previous amount.

### Requirement: Cropped review never exposes the uncropped output

For an AI-cropped capture, the result preview SHALL only show and save images derived from the cropped photo source.

#### Scenario: Adjust and save a cropped image

- **WHEN** the user adjusts the preset amount for an AI-cropped capture
- **THEN** each generated preview is based on the cropped image
- **AND** no uncropped full-frame photo is offered for saving.
