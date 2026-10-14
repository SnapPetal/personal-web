interface Env {
  FAMILY_PASSCODE: string;
  SESSION_SECRET: string;
}

interface SubtleCrypto {
  timingSafeEqual(left: ArrayBuffer | Uint8Array, right: ArrayBuffer | Uint8Array): boolean;
}
