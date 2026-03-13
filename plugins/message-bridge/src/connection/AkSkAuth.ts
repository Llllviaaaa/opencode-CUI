import { createHmac, randomUUID } from 'crypto';

export interface AkSkAuthPayload {
  ak: string;
  ts: string;
  nonce: string;
  sign: string;
}

/**
 * Interface for managing Access Key and Secret Key authentication
 */
export interface AkSkAuth {
  generateAuthPayload(): AkSkAuthPayload;
}

/**
 * Concrete implementation of AkSkAuth
 */
export class DefaultAkSkAuth implements AkSkAuth {
  private readonly accessKey: string;
  private readonly secretKey: string;

  constructor(accessKey: string, secretKey: string) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }

  generateAuthPayload(): AkSkAuthPayload {
    // AI-Gateway expects Unix seconds.
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID();
    // AI-Gateway verifies HMAC-SHA256(SK, "AKTSNONCE"), Base64 encoded.
    const sign = createHmac('sha256', this.secretKey)
      .update(`${this.accessKey}${ts}${nonce}`)
      .digest('base64');

    return {
      ak: this.accessKey,
      ts,
      nonce,
      sign,
    };
  }
}
