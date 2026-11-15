export const loganInit: (cacheDir: string, logDir: string, maxFile: number, key16: string, iv16: string) => number;
export const loganOpen: (fileName: string) => number;
export const loganWrite: (flag: number, ts: number, content: string) => number;
export const loganFlush: () => number;
export const loganDebug: (on: number) => void;
export const listLogFiles: (dir: string) => string;
export const listDir: (dir: string) => string;
export const writeFileText: (path: string, text: string) => number;
export const deleteFile: (path: string) => number;
export const readFileText: (path: string) => string;

export const testCppCrash: () => number;
