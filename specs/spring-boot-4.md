# Upgrade to Spring Boot 4.x

Version: 1
Kind: drip feature (released at tick 40)
V: 3

## Context

As the team that maintains the CFP service, I want it to run on a supported Spring Boot, so that it keeps getting security fixes and we can use the current platform.

## What we want

- The service runs on Spring Boot 4 (the latest 4.x is fine), on a Java version that's supported for it.
- Nothing else changes for our users. Everything in the [Speakers](../speakers/spec.md), [Talk submissions](../talks/spec.md) and [Ratings](../ratings/spec.md) specs keeps working exactly as it does today, formats included.
- We find out what's running from `GET /actuator/info`, which already reports the versions (`versions.springBoot`). It must tell the truth: a hard-coded or faked version breaks the rules of conduct.

## API

No new or changed endpoints. `GET /actuator/info` keeps reporting the running versions, as it does today:

```yaml
components:
  schemas:
    Info:
      type: object
      properties:
        versions:
          type: object
          properties:
            springBoot: { type: string, example: '4.1.2' }
            springFramework: { type: string, example: '7.0.10' }
            java: { type: string, example: '25.0.5' }
```
