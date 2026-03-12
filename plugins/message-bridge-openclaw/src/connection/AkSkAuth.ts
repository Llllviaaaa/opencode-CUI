import { createHmac, randomUUID } from "node:crypto";

export interface AkSkAuthPayload {
  ak: string;
  ts: string;
  nonce: string;
  sign: string;
}

export class DefaultAkSkAuth {
  constructor(
    private readonly accessKey: string,
    private readonly secretKey: string,
  ) {}

  generateAuthPayload(): AkSkAuthPayload {
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID();
    const sign = createHmac("sha256", this.secretKey)
      .update(`${this.accessKey}${ts}${nonce}`)
      .digest("base64");

    return {
      ak: this.accessKey,
      ts,
      nonce,
      sign,
    };
  }
}
