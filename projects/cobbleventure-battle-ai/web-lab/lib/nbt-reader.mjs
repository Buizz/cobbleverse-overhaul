import { readFile } from "node:fs/promises";
import { gunzipSync, inflateSync } from "node:zlib";

function safeLong(value) {
  return value >= Number.MIN_SAFE_INTEGER && value <= Number.MAX_SAFE_INTEGER
    ? Number(value)
    : value.toString();
}

class NbtCursor {
  constructor(buffer) {
    this.buffer = buffer;
    this.offset = 0;
  }

  take(length) {
    const start = this.offset;
    const end = start + length;
    if (end > this.buffer.length) {
      throw new Error(`Unexpected end of NBT at byte ${start}`);
    }
    this.offset = end;
    return this.buffer.subarray(start, end);
  }

  byte() {
    return this.take(1).readInt8(0);
  }

  unsignedByte() {
    return this.take(1).readUInt8(0);
  }

  short() {
    return this.take(2).readInt16BE(0);
  }

  unsignedShort() {
    return this.take(2).readUInt16BE(0);
  }

  int() {
    return this.take(4).readInt32BE(0);
  }

  long() {
    return safeLong(this.take(8).readBigInt64BE(0));
  }

  string() {
    return this.take(this.unsignedShort()).toString("utf8");
  }

  length(label) {
    const value = this.int();
    if (value < 0) throw new Error(`Negative ${label} length: ${value}`);
    return value;
  }

  payload(type) {
    switch (type) {
      case 0:
        return null;
      case 1:
        return this.byte();
      case 2:
        return this.short();
      case 3:
        return this.int();
      case 4:
        return this.long();
      case 5:
        return this.take(4).readFloatBE(0);
      case 6:
        return this.take(8).readDoubleBE(0);
      case 7: {
        const length = this.length("byte array");
        return [...this.take(length)].map((value) =>
          value > 127 ? value - 256 : value,
        );
      }
      case 8:
        return this.string();
      case 9: {
        const childType = this.unsignedByte();
        const length = this.length("list");
        return Array.from({ length }, () => this.payload(childType));
      }
      case 10: {
        const compound = {};
        while (true) {
          const childType = this.unsignedByte();
          if (childType === 0) return compound;
          compound[this.string()] = this.payload(childType);
        }
      }
      case 11: {
        const length = this.length("int array");
        return Array.from({ length }, () => this.int());
      }
      case 12: {
        const length = this.length("long array");
        return Array.from({ length }, () => this.long());
      }
      default:
        throw new Error(`Unsupported NBT tag type ${type} at byte ${this.offset}`);
    }
  }
}

function decompress(buffer) {
  if (buffer[0] === 0x1f && buffer[1] === 0x8b) return gunzipSync(buffer);
  if (buffer[0] === 0x78) {
    try {
      return inflateSync(buffer);
    } catch {
      return buffer;
    }
  }
  return buffer;
}

export function parseNbt(buffer) {
  const cursor = new NbtCursor(decompress(buffer));
  const type = cursor.unsignedByte();
  if (type === 0) return { name: "", value: null };
  const name = cursor.string();
  return { name, value: cursor.payload(type) };
}

export async function readNbt(filePath) {
  return parseNbt(await readFile(filePath));
}
