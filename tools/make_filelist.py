import os
import sys


def main():
    out = sys.argv[1]
    ext = sys.argv[2]
    roots = sys.argv[3:]
    files = []
    for root in roots:
        for base, _dirs, names in os.walk(root):
            for name in names:
                if name.endswith(ext):
                    files.append(os.path.relpath(os.path.join(base, name)).replace("\\", "/"))
    files.sort()
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for item in files:
            f.write(item + "\n")


if __name__ == "__main__":
    main()
