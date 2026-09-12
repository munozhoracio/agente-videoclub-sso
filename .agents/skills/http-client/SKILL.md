---
name: http-client
description: >-
  Guidelines and standards for authoring, formatting, and debugging RFC-style `.http`
  and `.rest` test files (compatible with VS Code REST Client and IntelliJ HTTP Client).
  Use this skill whenever creating, modifying, or fixing `.http` request files to avoid
  body corruption, parser errors, and cross-IDE incompatibilities.
---

# Guide for Authoring `.http` and `.rest` Test Files

This skill establishes strict authoring rules and patterns for writing `.http` files in this repository (e.g., `video_club.http`, `agent.http`).

---

## 1. Anatomy of a Valid `.http` File

```http
# File Header / Metadata
@host = localhost
@port = 8080
@base_url = http://{{host}}:{{port}}

# ====================================================================
# Section Title
# ====================================================================

###
# @name request_identifier
# Optional comment describing this request
METHOD {{base_url}}/path/to/resource
Header-Name: Header-Value
Content-Type: application/json

{
  "jsonKey": "jsonValue"
}

###
# @name next_request
GET {{base_url}}/another/endpoint
```

---

## 2. Mandatory Formatting Rules (The 5 Golden Rules)

### Rule 1: Clean Delimiter (`###`)
* **DO**: Place `###` strictly alone on its own line.
* **DON'T**: Never add titles or text on the delimiter line (e.g., `### Caso 1: Login`) when using `# @name`. In VS Code REST Client (`humao.rest-client`), text after `###` conflicts with `# @name` and breaks CodeLens request extraction.

### Rule 2: Request Name Directive (`# @name`)
* **DO**: Place `# @name <identifier>` on the immediate next line after `###`.
* **DO**: Use descriptive snake_case or camelCase identifiers (e.g., `# @name login_user`, `# @name agent_chat_list_movies`).

### Rule 3: Comments Placement (CRITICAL)
* **DO**: Place comments:
  1. Above `###` as section headers.
  2. Or directly between `# @name` and the `METHOD` line.
* **CRITICAL FORBIDDEN PATTERN**:
  **Never place comments between the closing brace `}` of a body and the next `###` delimiter.**
  ```http
  # ❌ WRONG: Breaks JSON parsing!
  {
    "prompt": "test"
  }

  # Caso 2: Siguiente request
  ###
  # @name next_request
  ```
  *Why it breaks*: VS Code REST Client treats *all lines* after the empty line following headers until `###` as the HTTP body. The comment `# Caso 2:...` gets concatenated into the payload, causing Spring Boot's Jackson parser to fail with `HttpMessageNotReadableException: Unexpected character ('#')` (HTTP 400).

### Rule 4: Header & Body Whitespace
* **DO**: Ensure exactly **ONE** blank line separates HTTP headers from the request payload.
* **DON'T**: Put empty lines inside the header block (an empty line signals the end of headers).

### Rule 5: Cross-IDE Test Scripts (`> {% ... %}`)
* **IntelliJ vs VS Code**: IntelliJ IDEA supports JavaScript response handlers (`> {% client.test(...) %}`).
* **VS Code REST Client does NOT support this syntax**: In VS Code, `> {%` is interpreted as redirecting the response to a file named `{%`, and any subsequent lines are treated as payload text.
* **RULE**: For files intended for VS Code / Antigravity IDE, **do not include `> {% ... %}` blocks after POST/PUT payloads**. Document expected responses in comments instead.

---

## 3. Standard Templates

### Template A: Direct Grant / Login Request
```http
###
# @name login
POST http://{{host}}:{{keycloak_port}}/realms/videoclub/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

client_id=videoclub-frontend&username=usuarioadmin&password=usuarioadmin&grant_type=password&scope=openid
```

### Template B: Authenticated Request with Token Chaining
Reference token dynamically from a prior login request using `{{<name>.response.body.<field>}}`:
```http
###
# @name get_movies_authenticated
GET {{base_url}}/movies
Authorization: Bearer {{login.response.body.access_token}}
Accept: application/json
```

### Template C: Clean JSON POST Request
```http
###
# @name create_entity
POST {{base_url}}/api/entities
Content-Type: application/json

{
  "name": "Interstellar",
  "year": 2014
}
```

---

## 4. Pre-Commit / Pre-Save Verification Checklist

Before saving any `.http` file, verify:
- [ ] Every request starts with a standalone `###` line.
- [ ] `# @name <unique_name>` is right below `###`.
- [ ] Exactly one blank line between headers and JSON body.
- [ ] Valid JSON syntax in body (no trailing commas, double-quoted keys).
- [ ] No comments between body closing `}` and next `###`.
- [ ] No `> {% ... %}` test blocks trailing behind JSON payloads in VS Code files.
- [ ] All variable interpolations match declared `@var` names.
