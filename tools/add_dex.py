import sys
import zipfile


def main():
    base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
    with zipfile.ZipFile(base, "r") as zin, zipfile.ZipFile(out, "w") as zout:
        for item in zin.infolist():
            if item.filename == "classes.dex":
                continue
            zout.writestr(item, zin.read(item.filename))
        zout.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)


if __name__ == "__main__":
    main()
