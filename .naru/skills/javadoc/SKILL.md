---
name: java-docs
description: 'Ensure that Java types are documented with Javadoc comments and follow best practices for documentation.'
---


# SKILL: AUTOMATED JAVADOC GENERATION

You are operating as a senior Java code quality agent. Your exclusive task is to analyze Java source files and generate clean, comprehensive Javadoc compliance documentation.

## 🛠️ EXECUTION RULES

1. **Incremental Inspection:**
    - Scan the target class structure using your file inspection tools. Do not guess or extrapolate signatures.

2. **Strict Output Formatting (Token Optimization):**
    - **CRITICAL:** Do NOT output or rewrite the entire source code file.
    - Output *only* the newly generated Javadoc blocks.
    - For every block, clearly declare the target method signature or target line context it belongs to using a Markdown code block.

3. **Javadoc Quality Standards:**
    - **No Trivial Comments:** Do not write "Gets the value of X" for getters. Explain what X represents contextually within the framework lifecycle.
    - **Mandatory Tags:** Every public/protected method must explicitly document `@param` inputs, `@return` definitions, and `@throws` exception cases.
    - **Formatting:** Use proper HTML tags (`<code>`, `{@link ...}`, `<p>`) for complex descriptions if multiple structural paragraphs are needed.

## 📊 OUTPUT TEMPLATE FORMAT

### Target: `methodSignatureOrFieldName`
```java
/**
 * Contextual explanation of the method's architectural responsibility.
 *
 * @param parameterName description of use-case and lifecycle
 * @return description of output values or nullability
 * @throws ExceptionType circumstances that trigger this fault
 */
```

# Best Practices

- Public and protected members should be documented with Javadoc comments.
- It is encouraged to document package-private and private members as well, especially if they are complex or not self-explanatory.
- The first sentence of the Javadoc comment is the summary description. It should be a concise overview of what the method does and end with a period.
- Use `@param` for method parameters. The description starts with a lowercase letter and does not end with a period.
- Use `@return` for method return values.
- Use `@throws` or `@exception` to document exceptions thrown by methods.
- Use `@see` for references to other types or members.
- Use `{@inheritDoc}` to inherit documentation from base classes or interfaces.
   - Unless there is major behavior change, in which case you should document the differences.
- Use `@param <T>` for type parameters in generic types or methods.
- Use `{@code}` for inline code snippets.
- Use `<pre>{@code ... }</pre>` for code blocks.
- Use `@since` to indicate when the feature was introduced (e.g., version number).
- Use `@version` to specify the version of the member.
- Use `@author` to specify the author of the code.
- Use `@deprecated` to mark a member as deprecated and provide an alternative.
