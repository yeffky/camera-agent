# Camera experience

## Purpose

Keep the camera usable for ordinary users while making style preview and optional AI guidance understandable and controllable.

## Requirements

### Requirement: Camera remains usable without cloud advice

The app SHALL allow ordinary preview and photo capture without a configured or reachable cloud service.

#### Scenario: Cloud service is unavailable

- **WHEN** the user opens the camera while offline or the configured AI request fails
- **THEN** the app keeps ordinary camera capture and local presets available
- **AND** the app communicates that AI advice is temporarily unavailable.

### Requirement: AI image submission is explicit

The app SHALL send a single current camera frame to the configured cloud model only after the user activates AI capture.

#### Scenario: User requests AI capture

- **WHEN** the user activates AI capture
- **THEN** the app captures one current frame for the request
- **AND** does not send a continuous preview stream or video.

### Requirement: Advanced camera controls remain deferred

The current iteration SHALL limit model-controlled camera changes to client-defined and device-supported basic controls.

#### Scenario: Model suggests an unsupported control

- **WHEN** the response contains an unknown or unsupported action
- **THEN** the client does not execute that action
- **AND** reports that it was skipped.
