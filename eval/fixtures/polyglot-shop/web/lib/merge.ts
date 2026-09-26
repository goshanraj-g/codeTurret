type Obj = Record<string, any>;

function isObject(value: unknown): value is Obj {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function deepMerge(target: Obj, source: Obj): Obj {
  for (const key of Object.keys(source)) {
    if (isObject(source[key])) {
      if (!isObject(target[key])) target[key] = {};
      deepMerge(target[key], source[key]);
    } else {
      target[key] = source[key];
    }
  }
  return target;
}
