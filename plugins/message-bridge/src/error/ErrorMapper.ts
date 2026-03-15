import { ERROR_CODES, type ErrorCode } from '../types/index.js';

export class ErrorMapper {
  fromSDKError(error: Error): ErrorCode {
    const errorMessage = error.message.toLowerCase();
    
    if (errorMessage.includes('timeout')) {
      return ERROR_CODES[1];
    }
    
    if (errorMessage.includes('connection') || errorMessage.includes('network') || errorMessage.includes('fetch')) {
      return ERROR_CODES[2];
    }
    
    return ERROR_CODES[2];
  }

  fromValidationError(errors: string[]): ErrorCode {
    return ERROR_CODES[4];
  }
}
