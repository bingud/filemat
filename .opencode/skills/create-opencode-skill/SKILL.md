---
name: create-opencode-skill
description: Use this skill to create or update OpenCode Skills or skill files.
---

## Context
OpenCode uses a "Skills" system to store reusable project knowledge. Skills are stored in `.opencode/skills/` with a specific folder-based structure.

## Rules for Creating Skills
When I ask you to "save this as a skill" or "create a skill for X":
1.  **Directory Structure**: Create a new directory inside `.opencode/skills/` named after the skill (use `kebab-case`).
2.  **File Naming**: Inside that directory, create a file exactly named `SKILL.md` (must be uppercase).
3.  **Frontmatter**: Every `SKILL.md` MUST start with this YAML block:
    ---
    name: [kebab-case-name]
    description: [One sentence describing when to use this skill]
    ---
4.  **Content**: 
    - Use clear headings (##).
    - Use imperative language ("Always do X", "Never use Y").
    - Provide a short code example of the pattern if applicable.

## Workflow
- Check if a skill with a similar name already exists before creating a new one.
- If updating an existing skill, always ask for confirmation.