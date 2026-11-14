-- Copyright 2026 The Orbit authors
-- SPDX-License-Identifier: Apache-2.0
-- A name the household gives a device ("Kitchen", "Bedroom"): shown in the stream-limit prompt and the device lists.
-- Empty = not named (clients then show the profile, else the device label in "device").
ALTER TABLE account_sessions ADD COLUMN name TEXT NOT NULL DEFAULT '';
