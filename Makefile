.PHONY: test test-tools check-python plugin legacy fabric forge neoforge

# These are release/protocol-tool unit tests. Real Minecraft behavior is checked
# separately by the native runners documented in docs/.
test: test-tools

test-tools:
	python3 -m unittest discover -s tools -p 'test_*.py' -v

check-python:
	python3 -m compileall -q tools

plugin:
	python3 tools/plugin-build.py --all

legacy:
	python3 tools/legacy-build.py

fabric:
	python3 tools/modern-build.py --all --platform fabric

forge:
	python3 tools/modern-build.py --all --platform forge

neoforge:
	python3 tools/modern-build.py --all --platform neoforge
