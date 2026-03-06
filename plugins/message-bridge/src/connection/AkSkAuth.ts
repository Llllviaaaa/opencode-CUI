import { createHmac, randomUUID } from 'crypto';

/**
 * Interface for managing Access Key and Secret Key authentication
 */
export interface AkSkAuth {
  generateAuthHeaders(): Record<string, string>;
  validateCredentials(): Promise<boolean>;
  getAccessKey(): string;
  getSecretKey(): string;
  generateQueryParams(): URLSearchParams;
}

/**
 * Concrete implementation of AkSkAuth
 */
export class DefaultAkSkAuth implements AkSkAuth {
  private readonly _accessKey: string;
  private readonly _secretKey: string;

  constructor(accessKey: string, secretKey: string) {
    this._accessKey = accessKey;
    this._secretKey = secretKey;
  }

  generateAuthHeaders(): Record<string, string> {
    const query = this.generateQueryParams();
    return {
      'Authorization': `Bearer ${query.get('sign')}`,
      'X-Bridge-AK': this._accessKey,
      'X-Bridge-TS': query.get('ts') ?? '',
      'X-Bridge-Nonce': query.get('nonce') ?? '',
      'Content-Type': 'application/json',
    };
  }

  generateQueryParams(): URLSearchParams {
    const ts = Date.now().toString();
    const nonce = randomUUID();
    const sign = createHmac('sha256', this._secretKey)
      .update(`${this._accessKey}:${ts}:${nonce}`)
      .digest('hex');

    return new URLSearchParams({
      ak: this._accessKey,
      ts,
      nonce,
      sign,
    });
  }

  async validateCredentials(): Promise<boolean> {
    // Basic validation - should probably call an endpoint to verify credentials
    return Promise.resolve(this._accessKey.length > 0 && this._secretKey.length > 0);
  }

  getAccessKey(): string {
    return this._accessKey;
  }

  getSecretKey(): string {
    return this._secretKey;
  }
}
