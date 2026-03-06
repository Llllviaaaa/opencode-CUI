import { ErrorCode } from '../types';

export class ErrorMapper {
  fromSDKError(error: Error): ErrorCode {
    const errorMessage = error.message.toLowerCase();
    
    if (errorMessage.includes('timeout')) {
      return 'SDK_TIMEOUT';
    }
    
    if (errorMessage.includes('connection') || errorMessage.includes('network') || errorMessage.includes('fetch')) {
      return 'SDK_UNREACHABLE';
    }
    
    return 'SDK_UNREACHABLE';
  }

  fromValidationError(errors: string[]): ErrorCode {
    return 'INVALID_PAYLOAD';
  }
}