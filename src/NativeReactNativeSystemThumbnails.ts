import {
  TurboModuleRegistry,
  type CodegenTypes,
  type TurboModule,
} from 'react-native';

export type NativeThumbnailResult = Readonly<{
  uri: string;
  width: CodegenTypes.Int32;
  height: CodegenTypes.Int32;
  kind: string;
  mimeType: string;
  fromCache: boolean;
}>;

export interface Spec extends TurboModule {
  generate(
    requestId: string,
    uri: string,
    width: CodegenTypes.Int32,
    height: CodegenTypes.Int32,
    format: string,
    quality: number,
    useCache: boolean,
    allowIconFallback: boolean
  ): Promise<NativeThumbnailResult>;

  cancel(requestId: string): void;

  clearCache(): Promise<CodegenTypes.Int32>;
}

export default TurboModuleRegistry.getEnforcing<Spec>(
  'ReactNativeSystemThumbnails'
);
