The loan is for: <purpose>

The images are the first pages of a document of a new type: "<typeName>". Write the JSON Schema for the data a lender would take from every document of this type, not only this one: names, dates, amounts, account and reference numbers, and periods.

The schema must follow these rules:
- The root is "type": "object" with at least one property.
- Every object lists all of its properties in "required" and sets "additionalProperties": false.
- A value that may be missing allows null, for example "type": ["string", "null"].
- Use lowerCamelCase property names, with a "description" for each.
- Don't use "$ref", "$defs", "definitions", "allOf" or "oneOf"; write every object out in full.

Answer with the schema itself, as a JSON object.
