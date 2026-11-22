# 0003. Javadoc, sources and signing

Status: accepted

## Decision

- javadoc gets a named module's dependencies split as javac got them (jpm 0.8.1+ reports the module path /
  class path split): jars that are not modules stay on the class path, because as automatic modules two
  that share a package would stop javadoc. Other projects take their dependencies from the class path
  (`Artifacts`, `Project`).
- Signing with a key file uses a throwaway keyring in a short temporary path, because gpg-agent's socket
  path must fit in about 100 bytes. The folder is made private to the user (on Windows the temp folder
  is already). Removing it afterwards may fail while the agent still holds its socket; it is a temp
  folder either way.
