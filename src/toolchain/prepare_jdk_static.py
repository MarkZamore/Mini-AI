"""Patch pinned build sources, preserving /MD for developer JVM/shared targets."""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
source = root / "build/static-toolchain/sources/labs-openjdk"
flags = source / "make/common/native/Flags.gmk"
text = flags.read_text()
anchor = "  $1_BASE_ASFLAGS := $$($2_ASFLAGS) $$($2_EXTRA_ASFLAGS)\n"
insertion = """  # EnterpriseAI: only static archive objects use the statically linked CRT.
  ifeq ($(STATIC_LIBS), true)
    ifeq ($(TOOLCHAIN_TYPE), microsoft)
      $1_BASE_CFLAGS := $$(filter-out -MD /MD -MDd /MDd, $$($1_BASE_CFLAGS)) -MT
      $1_BASE_CXXFLAGS := $$(filter-out -MD /MD -MDd /MDd, $$($1_BASE_CXXFLAGS)) -MT
    endif
  endif
"""
if "# EnterpriseAI: only static archive" not in text:
    if text.count(anchor) != 1:
        raise ValueError("Pinned per-object flags anchor changed")
    flags.with_suffix(".gmk.upstream").write_text(text)
    flags.write_text(text.replace(anchor, anchor + insertion))

vs = source / "make/autoconf/toolchain_microsoft.m4"
text = vs.read_text()
if "VS_DESCRIPTION_2026" not in text:
    vs.with_suffix(".m4.upstream").write_text(text)
    start = text.index('VS_DESCRIPTION_2022=')
    end = text.index('\n################################################################################', start)
    definitions = text[start:end].replace("2022", "2026").replace("=143", "=145")
    definitions = definitions.replace("VS170COMNTOOLS", "VS180COMNTOOLS")
    definitions = definitions.replace('Microsoft Visual Studio/2026', 'Microsoft Visual Studio/18')
    definitions = definitions.replace('"v143"', '"v145"')
    text = text.replace('VALID_VS_VERSIONS="2022 2019"', 'VALID_VS_VERSIONS="2026 2022 2019"')
    text = text[:text.index('\n################################################################################', text.index('VS_DESCRIPTION_2022='))] + '\n' + definitions + text[text.index('\n################################################################################', text.index('VS_DESCRIPTION_2022=')):]
    vs.write_text(text)
print(json.dumps({"source": str(source), "staticArchivesCRT": "/MT", "developerJVMCRT": "/MD", "visualStudio": "2026 / MSVC 14.51.36231"}))
