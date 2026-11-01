#!/usr/bin/env python3
"""Checks the low-level-designs writing rules (see ../CLAUDE.md, "Writing rules").

Usage: python3 scripts/check_style.py PATH [PATH ...]

Each PATH can be a file or a folder. Folders are searched for *.java and *.md files (skipping target/, _legacy-app/ and *_old.md).

Two checks:
  1. Every Java type, constructor and method (tests included) has a Javadoc comment (/** ... */) right above it. Annotations in between are fine.
  2. No soft wrap: a sentence is never split across lines in Markdown prose, Javadoc, or // comments.

Exit code 0 means clean. Otherwise it prints one line per problem: file:line: message.
"""
import re
import sys
from pathlib import Path

SKIP_DIRS = {"target", "_legacy-app", "node_modules", ".git"}

# Lines that start a Markdown block element. A line starting with one of these is never a wrapped continuation.
MD_BLOCK_START = re.compile(r"^\s*([-*+] |\d+\. |\||#|>|<|```|~~~|!\[|\*\*Self-grade|---$|===$)")

JAVA_KEYWORDS_NOT_DECL = {"if", "for", "while", "switch", "catch", "return", "new", "else", "do", "try", "throw", "synchronized", "case", "yield", "assert", "super", "this"}

TYPE_DECL = re.compile(r"^\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(class|interface|enum|record|@interface)\s+(\w+)")
METHOD_DECL = re.compile(r"^\s*(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|strictfp)\s+)*(?:<[^>]+>\s+)?(?:[\w.$]+(?:<[^()]*?>)?(?:\[\])*\s+)?(\w+)\s*\(")
COMPACT_CTOR = re.compile(r"^\s*(?:public|protected|private)?\s*([A-Z]\w*)\s*\{\s*$")
DOC_TAG_START = re.compile(r"^\s*\*\s*(@|<p>|<p |</?ul>|</?ol>|<li>|</?pre>|</?table>|<tr>|</?blockquote>|\{@|-\s|\d+\.\s|$)")


def files_under(paths):
    """Yields every .java and .md file under the given paths, skipping build output and history files."""
    for p in map(Path, paths):
        if p.is_file():
            yield p
            continue
        for f in sorted(p.rglob("*")):
            if any(part in SKIP_DIRS for part in f.parts) or f.name.endswith("_old.md"):
                continue
            if f.suffix in (".java", ".md") and f.is_file():
                yield f


def strip_strings(line):
    """Removes string and char literals from a Java line so braces and parens inside them don't confuse the checks."""
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', '""', line)


def previous_significant(lines, i):
    """Returns the index of the closest line above i that is not blank and not an annotation, or -1."""
    j = i - 1
    depth = 0
    while j >= 0:
        s = lines[j].strip()
        if depth > 0:
            depth += s.count(")") - s.count("(")
            j -= 1
            continue
        if not s:
            j -= 1
            continue
        if s.startswith("@"):
            depth = s.count("(") - s.count(")")
            j -= 1
            continue
        if s.endswith(")") and not s.startswith("*") and not s.startswith("//"):
            # The tail of a multi-line annotation such as @CsvSource({...}): walk back to its "@" line.
            k = j
            balance = 0
            while k >= 0:
                t = lines[k].strip()
                balance += t.count(")") - t.count("(")
                if t.startswith("@") and balance <= 0:
                    break
                k -= 1
            if k >= 0 and lines[k].strip().startswith("@"):
                j = k - 1
                continue
        return j
    return -1


def check_java(path, lines):
    """Reports Java declarations without a Javadoc, and Javadoc or // comments that are wrapped across lines."""
    problems = []
    in_block = False
    in_javadoc = False
    in_pre = False
    brace_depth = 0
    record_names = set()
    prev_doc_text = False
    prev_line_comment = False
    for i, raw in enumerate(lines):
        line = raw.rstrip("\n")
        s = line.strip()
        # Comment tracking and wrap checks.
        if in_block:
            if in_javadoc:
                if "<pre>" in s:
                    in_pre = True
                if not in_pre and s.startswith("*") and not s.startswith("*/"):
                    text = s.lstrip("*").strip()
                    # A line that starts a new block (tag, <p>, list item) is fine. Plain text right after other text is a wrapped sentence.
                    if text and prev_doc_text and not DOC_TAG_START.match(line):
                        problems.append((i + 1, "Javadoc sentence wrapped onto this line; join it to the line above"))
                    prev_doc_text = bool(text)
                if "</pre>" in s:
                    in_pre = False
            if "*/" in s:
                in_block = False
                in_javadoc = False
                prev_doc_text = False
            prev_line_comment = False
            continue
        if s.startswith("/**"):
            in_block = "*/" not in s[3:]
            in_javadoc = in_block
            prev_doc_text = bool(s[3:].strip()) and in_block
            prev_line_comment = False
            continue
        if s.startswith("/*"):
            in_block = "*/" not in s[2:]
            prev_line_comment = False
            continue
        if s.startswith("//"):
            if prev_line_comment:
                problems.append((i + 1, "// comment wrapped onto this line; join it to the line above"))
            prev_line_comment = True
            continue
        prev_line_comment = False

        code = strip_strings(line.split("//")[0])
        cs = code.strip()
        if not cs:
            continue

        decl = None
        t = TYPE_DECL.match(code)
        if t:
            decl = f"{t.group(1)} {t.group(2)}"
            if t.group(1) == "record":
                record_names.add(t.group(2))
        else:
            c = COMPACT_CTOR.match(code)
            if c and c.group(1) in record_names:
                decl = f"compact constructor {c.group(1)}"
            else:
                m = METHOD_DECL.match(code)
                if m and brace_depth >= 1:
                    name = m.group(1)
                    first = cs.split()[0].split("(")[0]
                    looks_like_decl = (
                        name not in JAVA_KEYWORDS_NOT_DECL
                        and first not in JAVA_KEYWORDS_NOT_DECL
                        and not cs.startswith(("return", "throw", "new ", "else"))
                        and "=" not in cs.split("(")[0]
                        and "." not in cs.split("(")[0].split()[-1]
                        and (cs.endswith("{") or cs.endswith(";") and ("abstract" in cs or is_interface_member(lines, i)) or cs.endswith(",") or cs.endswith("(") or ")" not in cs or re.search(r"\)\s*(throws [\w., ]+)?\s*\{?$", cs))
                        and not re.match(r"^\s*[\w.]+\s*\(.*\)\s*;\s*$", code)
                    )
                    if looks_like_decl and (len(cs.split("(")[0].split()) >= 2 or (name[:1].isupper() and cs.split("(")[0].strip() == name) or cs.split("(")[0].split()[0] in ("public", "private", "protected")):
                        decl = f"method {name}"
        if decl:
            j = previous_significant(lines, i)
            if j < 0 or not lines[j].strip().endswith("*/") or not is_javadoc_end(lines, j):
                problems.append((i + 1, f"{decl} has no Javadoc (/** ... */) above it"))

        brace_depth += code.count("{") - code.count("}")
    return problems


def is_interface_member(lines, i):
    """Guesses whether line i sits directly inside an interface, where abstract methods end with ';'."""
    depth = 0
    for j in range(i - 1, -1, -1):
        code = strip_strings(lines[j].split("//")[0])
        depth += code.count("}") - code.count("{")
        if depth < 0:
            return bool(re.search(r"\binterface\b", code))
    return False


def is_javadoc_end(lines, j):
    """Returns True if the comment that ends on line j started with '/**' (a Javadoc), not a plain '/*'."""
    k = j
    while k >= 0:
        s = lines[k].strip()
        if s.startswith("/**"):
            return True
        if s.startswith("/*"):
            return False
        k -= 1
    return False


def check_markdown(path, lines):
    """Reports Markdown prose lines that continue a sentence from the line above (soft wrap)."""
    problems = []
    in_fence = False
    in_html_comment = False
    prev_prose = False
    for i, raw in enumerate(lines):
        line = raw.rstrip("\n")
        s = line.strip()
        if s.startswith("```") or s.startswith("~~~"):
            in_fence = not in_fence
            prev_prose = False
            continue
        if in_fence:
            continue
        if "<!--" in s and "-->" not in s:
            in_html_comment = True
        if in_html_comment:
            if "-->" in s:
                in_html_comment = False
            prev_prose = False
            continue
        if not s:
            prev_prose = False
            continue
        starts_block = bool(MD_BLOCK_START.match(line))
        if not starts_block and prev_prose:
            problems.append((i + 1, "Markdown text wrapped onto this line; join it to the line above"))
        # A table row or heading is never continued, everything else (paragraph text or list item) can be.
        prev_prose = not s.startswith(("|", "#", "<", "---"))
    return problems


def main(argv):
    """Runs both checks over every path given on the command line and prints the problems found."""
    if len(argv) < 2:
        print(__doc__)
        return 2
    total = 0
    for f in files_under(argv[1:]):
        lines = f.read_text(encoding="utf-8").splitlines()
        problems = check_java(f, lines) if f.suffix == ".java" else check_markdown(f, lines)
        for line_no, msg in problems:
            print(f"{f}:{line_no}: {msg}")
        total += len(problems)
    print(f"{total} problem(s)")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
