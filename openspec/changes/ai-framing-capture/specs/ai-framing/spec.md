# AI framing capture

## ADDED Requirements

### Requirement: AI response can specify a bounded crop

The cloud response MAY contain a normalized crop rectangle in the documented upright, non-mirrored image coordinate space. The client SHALL validate finite coordinates, ordering, area, aspect ratio, freshness, and image mapping before applying it.

#### Scenario: Valid crop advice is returned

- **WHEN** a fresh AI response contains a crop rectangle that passes client validation
- **THEN** the camera screen shows the resulting crop before capture
- **AND** the saved photo is cropped from the full-resolution camera capture using the mapped region.

#### Scenario: Crop advice is missing or invalid

- **WHEN** the response is legacy, malformed, expired, or cannot map safely to the current photo output
- **THEN** the client does not apply an uncertain crop
- **AND** automatic capture does not run because of that response.

### Requirement: User controls the capture mode

The app SHALL support user-confirmed shutter capture and an explicitly selected automatic capture mode after valid AI advice.

#### Scenario: Manual capture mode

- **WHEN** valid AI advice has been applied in manual mode
- **THEN** the app presents the adjusted composition
- **AND** captures only after the user presses the shutter.

#### Scenario: Automatic capture mode

- **WHEN** the user explicitly selected automatic mode and valid fresh advice has been fully applied
- **THEN** the app captures once after presenting the adjusted composition
- **AND** does not auto-capture after network, parsing, validation, or crop failures.

### Requirement: Only cropped output is saved

The app SHALL save only the user-approved or automatically captured cropped photo and SHALL remove temporary uncropped processing data after completion.

#### Scenario: Crop output succeeds

- **WHEN** the cropped photo is encoded and saved successfully
- **THEN** the saved result contains the cropped composition
- **AND** no uncropped original is offered or retained as a separate output.

### Requirement: Basic camera actions remain bounded

The app SHALL apply only known, fresh, device-supported camera actions within client-defined value and change limits.

#### Scenario: Response contains an unsupported action

- **WHEN** the response contains a known action that the current device does not support
- **THEN** the app skips that action, preserves the current camera state, and reports the result.

#### Scenario: Response contains an unknown action kind

- **WHEN** the response contains an action kind outside the client allowlist
- **THEN** the client rejects the response without applying its actions
- **AND** reports that the cloud response is invalid.

### Requirement: Cloud errors preserve ordinary capture

The app SHALL preserve ordinary camera capture when the AI service is unavailable.

#### Scenario: AI request cannot complete

- **WHEN** a request fails because of network, service, or response errors
- **THEN** the app shows that AI is unavailable
- **AND** ordinary camera capture remains available
- **AND** the failed request does not trigger automatic capture.

### Requirement: Crop suggestion is visible in the current camera screen

The app SHALL display a validated crop suggestion on the current camera screen before the cropped photo is captured.

#### Scenario: AI crop is inside the visible preview

- **WHEN** a valid crop rectangle maps fully inside the current PreviewView content
- **THEN** the app animates a crop frame over the viewfinder
- **AND** the user can see the frame before using the shutter.

#### Scenario: AI crop falls outside the visible preview

- **WHEN** the suggested crop cannot be shown faithfully inside the current PreviewView content
- **THEN** the app skips that crop suggestion
- **AND** automatic capture is not triggered.
