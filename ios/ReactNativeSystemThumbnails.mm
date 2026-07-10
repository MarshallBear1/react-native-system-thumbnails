#import "ReactNativeSystemThumbnails.h"

#import <CommonCrypto/CommonDigest.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#import <QuickLookThumbnailing/QuickLookThumbnailing.h>

#include <errno.h>
#include <math.h>

static NSString *const RNSTErrorDomain = @"ReactNativeSystemThumbnails";

static NSString *const RNSTInvalidArgument = @"INVALID_ARGUMENT";
static NSString *const RNSTUnsupportedURI = @"UNSUPPORTED_URI";
static NSString *const RNSTNotFound = @"NOT_FOUND";
static NSString *const RNSTPermissionDenied = @"PERMISSION_DENIED";
static NSString *const RNSTCancelled = @"CANCELLED";
static NSString *const RNSTGenerationFailed = @"GENERATION_FAILED";
static NSString *const RNSTCacheError = @"CACHE_ERROR";

static NSInteger const RNSTMaximumDimension = 4096;
static uint64_t const RNSTMaximumPixelCount = 4096ULL * 4096ULL;

@interface RNSTThumbnailOperation : NSObject

@property (nonatomic, copy) NSString *requestId;
@property (nonatomic, copy, nullable) RCTPromiseResolveBlock resolve;
@property (nonatomic, copy, nullable) RCTPromiseRejectBlock reject;
@property (nonatomic, strong, nullable) NSURL *sourceURL;
@property (nonatomic, strong, nullable) QLThumbnailGenerationRequest *thumbnailRequest;
@property (nonatomic) BOOL accessingSecurityScopedResource;
@property (nonatomic) BOOL cancelled;
@property (nonatomic) BOOL finished;

@end

@implementation RNSTThumbnailOperation
@end

static NSError *RNSTMakeError(NSString *message, NSError *_Nullable underlyingError)
{
  NSMutableDictionary *userInfo = [NSMutableDictionary dictionaryWithObject:message
                                                                      forKey:NSLocalizedDescriptionKey];
  if (underlyingError != nil) {
    userInfo[NSUnderlyingErrorKey] = underlyingError;
  }
  return [NSError errorWithDomain:RNSTErrorDomain code:1 userInfo:userInfo];
}

static void RNSTReject(
    RCTPromiseRejectBlock reject,
    NSString *code,
    NSString *message,
    NSError *_Nullable underlyingError)
{
  reject(code, message, RNSTMakeError(message, underlyingError));
}

static BOOL RNSTErrorContainsCode(NSError *_Nullable error, NSString *domain, NSInteger code)
{
  NSError *candidate = error;
  for (NSUInteger depth = 0; candidate != nil && depth < 8; depth++) {
    if ([candidate.domain isEqualToString:domain] && candidate.code == code) {
      return YES;
    }
    id underlying = candidate.userInfo[NSUnderlyingErrorKey];
    candidate = [underlying isKindOfClass:[NSError class]] ? underlying : nil;
  }
  return NO;
}

static BOOL RNSTErrorIsNotFound(NSError *_Nullable error)
{
  return RNSTErrorContainsCode(error, NSCocoaErrorDomain, NSFileNoSuchFileError) ||
      RNSTErrorContainsCode(error, NSCocoaErrorDomain, NSFileReadNoSuchFileError) ||
      RNSTErrorContainsCode(error, NSPOSIXErrorDomain, ENOENT) ||
      RNSTErrorContainsCode(error, NSPOSIXErrorDomain, ENOTDIR);
}

static BOOL RNSTErrorIsPermissionDenied(NSError *_Nullable error)
{
  return RNSTErrorContainsCode(error, NSCocoaErrorDomain, NSFileReadNoPermissionError) ||
      RNSTErrorContainsCode(error, NSCocoaErrorDomain, NSFileWriteNoPermissionError) ||
      RNSTErrorContainsCode(error, NSPOSIXErrorDomain, EACCES) ||
      RNSTErrorContainsCode(error, NSPOSIXErrorDomain, EPERM);
}

static BOOL RNSTErrorIsCancellation(NSError *_Nullable error)
{
  return RNSTErrorContainsCode(error, QLThumbnailErrorDomain, QLThumbnailErrorRequestCancelled) ||
      RNSTErrorContainsCode(error, NSCocoaErrorDomain, NSUserCancelledError);
}

static NSString *RNSTSHA256(NSData *data)
{
  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(data.bytes, (CC_LONG)data.length, digest);

  static const char hex[] = "0123456789abcdef";
  char result[CC_SHA256_DIGEST_LENGTH * 2 + 1];
  for (NSUInteger index = 0; index < CC_SHA256_DIGEST_LENGTH; index++) {
    result[index * 2] = hex[(digest[index] >> 4) & 0x0f];
    result[index * 2 + 1] = hex[digest[index] & 0x0f];
  }
  result[CC_SHA256_DIGEST_LENGTH * 2] = '\0';
  return [NSString stringWithUTF8String:result];
}

static NSString *RNSTCanonicalFormat(NSString *format)
{
  NSString *value = format.lowercaseString;
  if ([value isEqualToString:@"png"]) {
    return @"png";
  }
  if ([value isEqualToString:@"jpg"] || [value isEqualToString:@"jpeg"]) {
    return @"jpeg";
  }
  return nil;
}

static NSString *RNSTFileExtensionForFormat(NSString *format)
{
  return [format isEqualToString:@"jpeg"] ? @"jpg" : @"png";
}

static NSString *RNSTMIMETypeForFormat(NSString *format)
{
  return [format isEqualToString:@"jpeg"] ? @"image/jpeg" : @"image/png";
}

static NSURL *_Nullable RNSTCacheDirectoryURL(NSError **error)
{
  NSURL *cachesURL = [[NSFileManager defaultManager] URLsForDirectory:NSCachesDirectory
                                                            inDomains:NSUserDomainMask]
                         .firstObject;
  if (cachesURL == nil) {
    if (error != NULL) {
      *error = RNSTMakeError(@"The application cache directory is unavailable.", nil);
    }
    return nil;
  }

  NSURL *directoryURL = [cachesURL URLByAppendingPathComponent:@"react-native-system-thumbnails"
                                                   isDirectory:YES];
  if (![[NSFileManager defaultManager] createDirectoryAtURL:directoryURL
                                withIntermediateDirectories:YES
                                                 attributes:nil
                                                      error:error]) {
    return nil;
  }
  return directoryURL;
}

static NSURL *_Nullable RNSTResolveSourceURL(
    NSString *uri,
    NSString **rejectionCode,
    NSError **error)
{
  if (uri.length == 0) {
    *rejectionCode = RNSTInvalidArgument;
    *error = RNSTMakeError(@"uri must not be empty.", nil);
    return nil;
  }

  NSRange schemeSeparator = [uri rangeOfString:@"://"];
  if (![uri hasPrefix:@"/"] && schemeSeparator.location != NSNotFound) {
    NSString *scheme = [[uri substringToIndex:schemeSeparator.location] lowercaseString];
    if ([scheme isEqualToString:@"bundle"]) {
      NSString *resourcePath = [uri substringFromIndex:NSMaxRange(schemeSeparator)];
      if ([resourcePath rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"?#"]].location !=
          NSNotFound) {
        *rejectionCode = RNSTInvalidArgument;
        *error = RNSTMakeError(@"bundle:// URIs must not contain a query or fragment.", nil);
        return nil;
      }

      resourcePath = [resourcePath stringByRemovingPercentEncoding];
      while ([resourcePath hasPrefix:@"/"]) {
        resourcePath = [resourcePath substringFromIndex:1];
      }
      if (resourcePath.length == 0) {
        *rejectionCode = RNSTInvalidArgument;
        *error = RNSTMakeError(@"bundle:// must be followed by a resource path.", nil);
        return nil;
      }

      for (NSString *component in [resourcePath pathComponents]) {
        if ([component isEqualToString:@"."] || [component isEqualToString:@".."]) {
          *rejectionCode = RNSTInvalidArgument;
          *error = RNSTMakeError(@"bundle:// resource paths must not traverse directories.", nil);
          return nil;
        }
      }

      NSURL *resourceRoot = NSBundle.mainBundle.resourceURL;
      if (resourceRoot == nil) {
        *rejectionCode = RNSTNotFound;
        *error = RNSTMakeError(@"The main bundle has no resource directory.", nil);
        return nil;
      }

      NSURL *resourceURL = [[resourceRoot URLByAppendingPathComponent:resourcePath]
          URLByStandardizingPath];
      NSString *rootPath = resourceRoot.URLByStandardizingPath.path;
      NSString *allowedPrefix = [rootPath stringByAppendingString:@"/"];
      if (![resourceURL.path hasPrefix:allowedPrefix]) {
        *rejectionCode = RNSTInvalidArgument;
        *error = RNSTMakeError(@"bundle:// resource path is outside the main bundle.", nil);
        return nil;
      }
      return resourceURL;
    }

    if (![scheme isEqualToString:@"file"]) {
      *rejectionCode = RNSTUnsupportedURI;
      *error = RNSTMakeError(
          [NSString stringWithFormat:@"Unsupported URI scheme '%@'.", scheme], nil);
      return nil;
    }
  }

  NSURL *sourceURL = nil;
  if ([uri hasPrefix:@"/"]) {
    sourceURL = [NSURL fileURLWithPath:uri];
  } else {
    NSURLComponents *components = [NSURLComponents componentsWithString:uri];
    if (components.scheme.length == 0) {
      *rejectionCode = RNSTInvalidArgument;
      *error = RNSTMakeError(@"uri must be an absolute path or a supported local URI.", nil);
      return nil;
    }
    if (![components.scheme.lowercaseString isEqualToString:@"file"] ||
        components.query != nil || components.fragment != nil) {
      *rejectionCode = [components.scheme.lowercaseString isEqualToString:@"file"]
          ? RNSTInvalidArgument
          : RNSTUnsupportedURI;
      *error = RNSTMakeError(@"Only local file:// and bundle:// URIs are supported.", nil);
      return nil;
    }
    if (components.host.length > 0 &&
        ![components.host.lowercaseString isEqualToString:@"localhost"]) {
      *rejectionCode = RNSTUnsupportedURI;
      *error = RNSTMakeError(@"Remote file URI hosts are not supported.", nil);
      return nil;
    }
    sourceURL = components.URL;
  }

  if (!sourceURL.isFileURL || !sourceURL.path.isAbsolutePath) {
    *rejectionCode = RNSTInvalidArgument;
    *error = RNSTMakeError(@"uri must resolve to an absolute local file path.", nil);
    return nil;
  }
  return sourceURL.URLByStandardizingPath;
}

static NSDictionary *_Nullable RNSTFileAttributes(
    NSURL *sourceURL,
    NSString **rejectionCode,
    NSError **error)
{
  NSError *attributesError = nil;
  NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:sourceURL.path
                                                                               error:&attributesError];
  if (attributes == nil) {
    if (RNSTErrorIsNotFound(attributesError)) {
      *rejectionCode = RNSTNotFound;
    } else if (RNSTErrorIsPermissionDenied(attributesError)) {
      *rejectionCode = RNSTPermissionDenied;
    } else {
      *rejectionCode = RNSTGenerationFailed;
    }
    *error = attributesError ?: RNSTMakeError(@"Unable to inspect the source file.", nil);
    return nil;
  }
  return attributes;
}

static NSString *_Nullable RNSTCacheKey(
    NSURL *sourceURL,
    NSDictionary *attributes,
    NSInteger width,
    NSInteger height,
    NSString *format,
    double quality,
    BOOL allowIconFallback,
    NSError **error)
{
  NSDate *modificationDate = attributes[NSFileModificationDate];
  NSDictionary *payload = @{
    @"version" : @1,
    @"path" : sourceURL.path.precomposedStringWithCanonicalMapping ?: @"",
    @"fileSize" : attributes[NSFileSize] ?: @0,
    @"modificationTime" : modificationDate != nil
        ? [NSString stringWithFormat:@"%.9f", modificationDate.timeIntervalSince1970]
        : @"0",
    @"fileType" : attributes[NSFileType] ?: @"",
    @"fileSystem" : attributes[NSFileSystemNumber] ?: @0,
    @"fileNumber" : attributes[NSFileSystemFileNumber] ?: @0,
    @"width" : @(width),
    @"height" : @(height),
    @"format" : format,
    @"quality" : [format isEqualToString:@"png"]
        ? @"lossless"
        : [NSString stringWithFormat:@"%.17g", quality],
    @"allowIconFallback" : @(allowIconFallback),
  };

  NSData *serialized = [NSJSONSerialization dataWithJSONObject:payload
                                                       options:NSJSONWritingSortedKeys
                                                         error:error];
  return serialized != nil ? RNSTSHA256(serialized) : nil;
}

static NSURL *RNSTCacheFileURL(
    NSURL *cacheDirectory,
    NSString *cacheKey,
    NSString *kind,
    NSString *format)
{
  NSString *filename = [NSString stringWithFormat:@"%@.%@.%@",
                                                  cacheKey,
                                                  kind,
                                                  RNSTFileExtensionForFormat(format)];
  return [cacheDirectory URLByAppendingPathComponent:filename isDirectory:NO];
}

static BOOL RNSTReadImageSize(NSURL *fileURL, NSInteger *width, NSInteger *height)
{
  CGImageSourceRef imageSource = CGImageSourceCreateWithURL((__bridge CFURLRef)fileURL, nil);
  if (imageSource == nil || CGImageSourceGetCount(imageSource) == 0) {
    if (imageSource != nil) {
      CFRelease(imageSource);
    }
    return NO;
  }

  NSDictionary *properties = CFBridgingRelease(
      CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil));
  CFRelease(imageSource);

  NSNumber *pixelWidth = properties[(NSString *)kCGImagePropertyPixelWidth];
  NSNumber *pixelHeight = properties[(NSString *)kCGImagePropertyPixelHeight];
  if (pixelWidth == nil || pixelHeight == nil ||
      pixelWidth.unsignedLongLongValue == 0 || pixelHeight.unsignedLongLongValue == 0 ||
      pixelWidth.unsignedLongLongValue > INT32_MAX ||
      pixelHeight.unsignedLongLongValue > INT32_MAX) {
    return NO;
  }

  *width = pixelWidth.integerValue;
  *height = pixelHeight.integerValue;
  return YES;
}

static CGImageRef _Nullable RNSTCreateGenericIcon(
    NSInteger width,
    NSInteger height,
    NSString *sourceExtension) CF_RETURNS_RETAINED
{
  if (width <= 0 || height <= 0 || (NSUInteger)width > SIZE_MAX / 4) {
    return nil;
  }

  size_t bytesPerRow = (size_t)width * 4;
  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGContextRef context = CGBitmapContextCreate(
      nil,
      (size_t)width,
      (size_t)height,
      8,
      bytesPerRow,
      colorSpace,
      (CGBitmapInfo)kCGImageAlphaPremultipliedLast |
          (CGBitmapInfo)kCGBitmapByteOrder32Big);
  CGColorSpaceRelease(colorSpace);
  if (context == nil) {
    return nil;
  }

  NSString *seed = sourceExtension.lowercaseString;
  if (seed.length == 0) {
    seed = @"file";
  }
  NSData *seedData = [seed dataUsingEncoding:NSUTF8StringEncoding];
  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(seedData.bytes, (CC_LONG)seedData.length, digest);
  CGFloat red = 0.25 + ((CGFloat)digest[0] / 255.0) * 0.45;
  CGFloat green = 0.30 + ((CGFloat)digest[1] / 255.0) * 0.40;
  CGFloat blue = 0.38 + ((CGFloat)digest[2] / 255.0) * 0.42;

  CGFloat canvasWidth = (CGFloat)width;
  CGFloat canvasHeight = (CGFloat)height;
  CGFloat insetX = canvasWidth * 0.14;
  CGFloat insetY = canvasHeight * 0.09;
  CGFloat left = insetX;
  CGFloat right = canvasWidth - insetX;
  CGFloat bottom = insetY;
  CGFloat top = canvasHeight - insetY;
  CGFloat fold = MIN((right - left) * 0.27, (top - bottom) * 0.22);

  CGMutablePathRef documentPath = CGPathCreateMutable();
  CGPathMoveToPoint(documentPath, nil, left, bottom);
  CGPathAddLineToPoint(documentPath, nil, right, bottom);
  CGPathAddLineToPoint(documentPath, nil, right, top - fold);
  CGPathAddLineToPoint(documentPath, nil, right - fold, top);
  CGPathAddLineToPoint(documentPath, nil, left, top);
  CGPathCloseSubpath(documentPath);

  CGContextAddPath(context, documentPath);
  CGContextSetRGBFillColor(context, red, green, blue, 1.0);
  CGContextFillPath(context);

  CGMutablePathRef foldPath = CGPathCreateMutable();
  CGPathMoveToPoint(foldPath, nil, right - fold, top);
  CGPathAddLineToPoint(foldPath, nil, right - fold, top - fold);
  CGPathAddLineToPoint(foldPath, nil, right, top - fold);
  CGPathCloseSubpath(foldPath);
  CGContextAddPath(context, foldPath);
  CGContextSetRGBFillColor(context,
                           MIN(red + 0.20, 1.0),
                           MIN(green + 0.20, 1.0),
                           MIN(blue + 0.20, 1.0),
                           1.0);
  CGContextFillPath(context);

  CGFloat panelInset = (right - left) * 0.17;
  CGRect panel = CGRectMake(
      left + panelInset,
      bottom + (top - bottom) * 0.22,
      (right - left) - panelInset * 2,
      (top - bottom) * 0.30);
  CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 0.90);
  CGContextFillRect(context, panel);

  CGFloat strokeWidth = MAX(1.0, MIN(canvasWidth, canvasHeight) * 0.025);
  CGContextAddPath(context, documentPath);
  CGContextSetRGBStrokeColor(context, 0.10, 0.12, 0.16, 0.45);
  CGContextSetLineWidth(context, strokeWidth);
  CGContextStrokePath(context);

  CGPathRelease(foldPath);
  CGPathRelease(documentPath);
  CGImageRef image = CGBitmapContextCreateImage(context);
  CGContextRelease(context);
  return image;
}

static CGImageRef _Nullable RNSTCreateBoundedImage(
    CGImageRef sourceImage,
    NSInteger maximumWidth,
    NSInteger maximumHeight) CF_RETURNS_RETAINED
{
  size_t sourceWidth = CGImageGetWidth(sourceImage);
  size_t sourceHeight = CGImageGetHeight(sourceImage);
  if (sourceWidth == 0 || sourceHeight == 0 || maximumWidth <= 0 || maximumHeight <= 0) {
    return nil;
  }

  double scale = MIN((double)maximumWidth / (double)sourceWidth,
                     (double)maximumHeight / (double)sourceHeight);
  if (!isfinite(scale) || scale <= 0.0) {
    return nil;
  }

  size_t targetWidth = MIN(
      (size_t)maximumWidth,
      MAX((size_t)1, (size_t)llround((double)sourceWidth * scale)));
  size_t targetHeight = MIN(
      (size_t)maximumHeight,
      MAX((size_t)1, (size_t)llround((double)sourceHeight * scale)));
  if (targetWidth > SIZE_MAX / 4) {
    return nil;
  }

  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGContextRef context = CGBitmapContextCreate(
      nil,
      targetWidth,
      targetHeight,
      8,
      targetWidth * 4,
      colorSpace,
      (CGBitmapInfo)kCGImageAlphaPremultipliedLast |
          (CGBitmapInfo)kCGBitmapByteOrder32Big);
  CGColorSpaceRelease(colorSpace);
  if (context == nil) {
    return nil;
  }

  CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
  CGContextDrawImage(
      context,
      CGRectMake(0, 0, (CGFloat)targetWidth, (CGFloat)targetHeight),
      sourceImage);
  CGImageRef boundedImage = CGBitmapContextCreateImage(context);
  CGContextRelease(context);
  return boundedImage;
}

static CGImageRef _Nullable RNSTCreateOpaqueImage(CGImageRef sourceImage) CF_RETURNS_RETAINED
{
  size_t width = CGImageGetWidth(sourceImage);
  size_t height = CGImageGetHeight(sourceImage);
  if (width == 0 || height == 0 || width > SIZE_MAX / 4) {
    return nil;
  }

  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGContextRef context = CGBitmapContextCreate(
      nil,
      width,
      height,
      8,
      width * 4,
      colorSpace,
      (CGBitmapInfo)kCGImageAlphaPremultipliedLast |
          (CGBitmapInfo)kCGBitmapByteOrder32Big);
  CGColorSpaceRelease(colorSpace);
  if (context == nil) {
    return nil;
  }

  CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0);
  CGContextFillRect(context, CGRectMake(0, 0, width, height));
  CGContextDrawImage(context, CGRectMake(0, 0, width, height), sourceImage);
  CGImageRef opaqueImage = CGBitmapContextCreateImage(context);
  CGContextRelease(context);
  return opaqueImage;
}

static NSData *_Nullable RNSTEncodeImage(
    CGImageRef image,
    NSString *format,
    double quality,
    NSError **error)
{
  CGImageRef imageToEncode = image;
  CGImageRef opaqueImage = nil;
  if ([format isEqualToString:@"jpeg"]) {
    opaqueImage = RNSTCreateOpaqueImage(image);
    if (opaqueImage == nil) {
      if (error != NULL) {
        *error = RNSTMakeError(@"Unable to prepare the thumbnail for JPEG encoding.", nil);
      }
      return nil;
    }
    imageToEncode = opaqueImage;
  }

  NSMutableData *data = [NSMutableData data];
  CFStringRef imageType = [format isEqualToString:@"jpeg"]
      ? CFSTR("public.jpeg")
      : CFSTR("public.png");
  CGImageDestinationRef destination = CGImageDestinationCreateWithData(
      (__bridge CFMutableDataRef)data, imageType, 1, nil);
  if (destination == nil) {
    if (opaqueImage != nil) {
      CGImageRelease(opaqueImage);
    }
    if (error != NULL) {
      *error = RNSTMakeError(@"Unable to create an image encoder.", nil);
    }
    return nil;
  }

  NSDictionary *properties = [format isEqualToString:@"jpeg"]
      ? @{(NSString *)kCGImageDestinationLossyCompressionQuality : @(quality)}
      : @{};
  CGImageDestinationAddImage(destination, imageToEncode, (__bridge CFDictionaryRef)properties);
  BOOL finalized = CGImageDestinationFinalize(destination);
  CFRelease(destination);
  if (opaqueImage != nil) {
    CGImageRelease(opaqueImage);
  }

  if (!finalized || data.length == 0) {
    if (error != NULL) {
      *error = RNSTMakeError(@"Image encoding failed.", nil);
    }
    return nil;
  }
  return data;
}

@interface ReactNativeSystemThumbnails ()

@property (nonatomic, strong) dispatch_queue_t workQueue;
@property (nonatomic, strong) NSLock *stateLock;
@property (nonatomic, strong) NSMutableDictionary<NSString *, RNSTThumbnailOperation *> *operations;
@property (nonatomic) BOOL invalidated;

@end

@implementation ReactNativeSystemThumbnails

- (instancetype)init
{
  self = [super init];
  if (self != nil) {
    dispatch_queue_attr_t attributes = dispatch_queue_attr_make_with_qos_class(
        DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0);
    _workQueue = dispatch_queue_create(
        "com.marshallbear1.react-native-system-thumbnails", attributes);
    _stateLock = [[NSLock alloc] init];
    _operations = [[NSMutableDictionary alloc] init];
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (BOOL)isOperationCancelled:(RNSTThumbnailOperation *)operation
{
  [self.stateLock lock];
  BOOL cancelled = operation.cancelled;
  [self.stateLock unlock];
  return cancelled;
}

- (void)finishOperation:(RNSTThumbnailOperation *)operation
                  result:(NSDictionary *_Nullable)result
          rejectionCode:(NSString *_Nullable)rejectionCode
                 message:(NSString *_Nullable)message
         underlyingError:(NSError *_Nullable)underlyingError
{
  [self.stateLock lock];
  if (operation.finished) {
    [self.stateLock unlock];
    return;
  }
  if (operation.cancelled) {
    result = nil;
    rejectionCode = RNSTCancelled;
    message = @"Thumbnail generation was cancelled.";
    underlyingError = nil;
  }
  operation.finished = YES;
  if (self.operations[operation.requestId] == operation) {
    [self.operations removeObjectForKey:operation.requestId];
  }

  BOOL stopSecurityScope = operation.accessingSecurityScopedResource;
  operation.accessingSecurityScopedResource = NO;
  NSURL *sourceURL = operation.sourceURL;
  RCTPromiseResolveBlock resolve = operation.resolve;
  RCTPromiseRejectBlock reject = operation.reject;
  operation.resolve = nil;
  operation.reject = nil;
  operation.thumbnailRequest = nil;
  [self.stateLock unlock];

  if (stopSecurityScope) {
    [sourceURL stopAccessingSecurityScopedResource];
  }

  if (result != nil) {
    resolve(result);
  } else {
    RNSTReject(reject, rejectionCode, message, underlyingError);
  }
}

- (void)finishCancelledOperation:(RNSTThumbnailOperation *)operation
{
  [self finishOperation:operation
                  result:nil
          rejectionCode:RNSTCancelled
                 message:@"Thumbnail generation was cancelled."
         underlyingError:nil];
}

- (void)startOperation:(RNSTThumbnailOperation *)operation
                    uri:(NSString *)uri
                  width:(NSInteger)width
                 height:(NSInteger)height
                 format:(NSString *)requestedFormat
                quality:(double)quality
               useCache:(BOOL)useCache
      allowIconFallback:(BOOL)allowIconFallback
{
  if ([self isOperationCancelled:operation]) {
    [self finishCancelledOperation:operation];
    return;
  }

  if (width <= 0 || height <= 0 || width > RNSTMaximumDimension ||
      height > RNSTMaximumDimension ||
      (uint64_t)width * (uint64_t)height > RNSTMaximumPixelCount) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTInvalidArgument
                   message:[NSString stringWithFormat:
                       @"width and height must be positive, at most %ld, and no more than %llu pixels in total.",
                       (long)RNSTMaximumDimension,
                       RNSTMaximumPixelCount]
           underlyingError:nil];
    return;
  }
  if (!isfinite(quality) || quality < 0.0 || quality > 1.0) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTInvalidArgument
                   message:@"quality must be a finite number between 0 and 1."
           underlyingError:nil];
    return;
  }

  NSString *format = RNSTCanonicalFormat(requestedFormat);
  if (format == nil) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTInvalidArgument
                   message:@"format must be 'png', 'jpg', or 'jpeg'."
           underlyingError:nil];
    return;
  }

  NSString *sourceRejectionCode = nil;
  NSError *sourceError = nil;
  NSURL *sourceURL = RNSTResolveSourceURL(uri, &sourceRejectionCode, &sourceError);
  if (sourceURL == nil) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:sourceRejectionCode
                   message:sourceError.localizedDescription
           underlyingError:sourceError];
    return;
  }

  BOOL startedSecurityScope = [sourceURL startAccessingSecurityScopedResource];
  [self.stateLock lock];
  operation.sourceURL = sourceURL;
  operation.accessingSecurityScopedResource = startedSecurityScope;
  BOOL cancelled = operation.cancelled;
  [self.stateLock unlock];
  if (cancelled) {
    [self finishCancelledOperation:operation];
    return;
  }

  NSDictionary *sourceAttributes = RNSTFileAttributes(
      sourceURL, &sourceRejectionCode, &sourceError);
  if (sourceAttributes == nil) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:sourceRejectionCode
                   message:sourceError.localizedDescription ?: @"Unable to inspect the source file."
           underlyingError:sourceError];
    return;
  }
  BOOL isDirectory = NO;
  [[NSFileManager defaultManager] fileExistsAtPath:sourceURL.path isDirectory:&isDirectory];
  if (isDirectory || [sourceAttributes[NSFileType] isEqualToString:NSFileTypeDirectory]) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTInvalidArgument
                   message:@"uri must refer to a file, not a directory."
           underlyingError:nil];
    return;
  }

  NSError *cacheError = nil;
  NSURL *cacheDirectory = RNSTCacheDirectoryURL(&cacheError);
  if (cacheDirectory == nil) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTCacheError
                   message:@"Unable to create the thumbnail cache directory."
           underlyingError:cacheError];
    return;
  }

  NSString *cacheKey = RNSTCacheKey(
      sourceURL,
      sourceAttributes,
      width,
      height,
      format,
      quality,
      allowIconFallback,
      &cacheError);
  if (cacheKey == nil) {
    [self finishOperation:operation
                    result:nil
            rejectionCode:RNSTCacheError
                   message:@"Unable to compute the thumbnail cache key."
           underlyingError:cacheError];
    return;
  }

  if (useCache) {
    NSArray<NSString *> *cachedKinds =
        allowIconFallback ? @[ @"thumbnail", @"icon" ] : @[ @"thumbnail" ];
    for (NSString *kind in cachedKinds) {
      NSURL *cachedURL = RNSTCacheFileURL(cacheDirectory, cacheKey, kind, format);
      if (![[NSFileManager defaultManager] fileExistsAtPath:cachedURL.path]) {
        continue;
      }

      NSInteger cachedWidth = 0;
      NSInteger cachedHeight = 0;
      if (RNSTReadImageSize(cachedURL, &cachedWidth, &cachedHeight) &&
          cachedWidth <= width && cachedHeight <= height) {
        if ([self isOperationCancelled:operation]) {
          [self finishCancelledOperation:operation];
          return;
        }
        [self finishOperation:operation
                        result:@{
                          @"uri" : cachedURL.absoluteString,
                          @"width" : @(cachedWidth),
                          @"height" : @(cachedHeight),
                          @"kind" : kind,
                          @"mimeType" : RNSTMIMETypeForFormat(format),
                          @"fromCache" : @YES,
                        }
                rejectionCode:nil
                       message:nil
               underlyingError:nil];
        return;
      }

      NSError *removeError = nil;
      if (![[NSFileManager defaultManager] removeItemAtURL:cachedURL error:&removeError] &&
          !RNSTErrorIsNotFound(removeError)) {
        [self finishOperation:operation
                        result:nil
                rejectionCode:RNSTCacheError
                       message:@"A corrupt cached thumbnail could not be removed."
               underlyingError:removeError];
        return;
      }
    }
  }

  QLThumbnailGenerationRequestRepresentationTypes representationTypes =
      QLThumbnailGenerationRequestRepresentationTypeThumbnail |
      QLThumbnailGenerationRequestRepresentationTypeLowQualityThumbnail;
  if (allowIconFallback) {
    representationTypes |= QLThumbnailGenerationRequestRepresentationTypeIcon;
  }
  QLThumbnailGenerationRequest *request = [[QLThumbnailGenerationRequest alloc]
      initWithFileAtURL:sourceURL
                   size:CGSizeMake((CGFloat)width, (CGFloat)height)
                  scale:1.0
    representationTypes:representationTypes];

  [self.stateLock lock];
  operation.thumbnailRequest = request;
  cancelled = operation.cancelled;
  [self.stateLock unlock];
  if (cancelled) {
    [self finishCancelledOperation:operation];
    return;
  }

  [QLThumbnailGenerator.sharedGenerator
      generateBestRepresentationForRequest:request
                         completionHandler:^(QLThumbnailRepresentation *_Nullable representation,
                                             NSError *_Nullable generationError) {
    // Retaining the module until Quick Look invokes its documented completion
    // keeps the promise and security-scoped source alive across a dev reload.
    dispatch_async(self.workQueue, ^{
      if ([self isOperationCancelled:operation] ||
          RNSTErrorIsCancellation(generationError)) {
        [self finishCancelledOperation:operation];
        return;
      }

      CGImageRef image = representation.CGImage;
      CGImageRef fallbackImage = nil;
      NSString *kind = nil;
      if (image != nil &&
          representation.type == QLThumbnailRepresentationTypeIcon &&
          !allowIconFallback) {
        image = nil;
      }
      if (image != nil) {
        kind = representation.type == QLThumbnailRepresentationTypeIcon
            ? @"icon"
            : @"thumbnail";
      } else if (allowIconFallback) {
        fallbackImage = RNSTCreateGenericIcon(width, height, sourceURL.path.pathExtension);
        image = fallbackImage;
        kind = @"icon";
      }

      if (image == nil) {
        NSString *code = RNSTGenerationFailed;
        if (RNSTErrorIsNotFound(generationError)) {
          code = RNSTNotFound;
        } else if (RNSTErrorIsPermissionDenied(generationError)) {
          code = RNSTPermissionDenied;
        }
        [self finishOperation:operation
                              result:nil
                      rejectionCode:code
                             message:@"Quick Look could not generate a thumbnail for this file."
                     underlyingError:generationError];
        return;
      }

      size_t pixelWidth = CGImageGetWidth(image);
      size_t pixelHeight = CGImageGetHeight(image);
      if (pixelWidth == 0 || pixelHeight == 0 ||
          pixelWidth > INT32_MAX || pixelHeight > INT32_MAX) {
        if (fallbackImage != nil) {
          CGImageRelease(fallbackImage);
        }
        [self finishOperation:operation
                              result:nil
                      rejectionCode:RNSTGenerationFailed
                             message:@"The generated thumbnail has invalid pixel dimensions."
                     underlyingError:generationError];
        return;
      }

      CGImageRef boundedImage = nil;
      if (pixelWidth > (size_t)width || pixelHeight > (size_t)height) {
        boundedImage = RNSTCreateBoundedImage(image, width, height);
        if (boundedImage == nil) {
          if (fallbackImage != nil) {
            CGImageRelease(fallbackImage);
          }
          [self finishOperation:operation
                                result:nil
                        rejectionCode:RNSTGenerationFailed
                               message:@"Unable to resize the generated thumbnail."
                       underlyingError:generationError];
          return;
        }
        image = boundedImage;
        pixelWidth = CGImageGetWidth(image);
        pixelHeight = CGImageGetHeight(image);
      }

      NSError *encodingError = nil;
      NSData *encodedImage = RNSTEncodeImage(image, format, quality, &encodingError);
      if (boundedImage != nil) {
        CGImageRelease(boundedImage);
      }
      if (fallbackImage != nil) {
        CGImageRelease(fallbackImage);
      }
      if (encodedImage == nil) {
        [self finishOperation:operation
                              result:nil
                      rejectionCode:RNSTGenerationFailed
                             message:@"Unable to encode the generated thumbnail."
                     underlyingError:encodingError];
        return;
      }
      if ([self isOperationCancelled:operation]) {
        [self finishCancelledOperation:operation];
        return;
      }

      NSString *alternateKind = [kind isEqualToString:@"thumbnail"] ? @"icon" : @"thumbnail";
      NSURL *alternateURL = RNSTCacheFileURL(cacheDirectory, cacheKey, alternateKind, format);
      NSError *removeError = nil;
      if ([[NSFileManager defaultManager] fileExistsAtPath:alternateURL.path] &&
          ![[NSFileManager defaultManager] removeItemAtURL:alternateURL error:&removeError]) {
        [self finishOperation:operation
                              result:nil
                      rejectionCode:RNSTCacheError
                             message:@"Unable to replace a stale cached thumbnail."
                     underlyingError:removeError];
        return;
      }

      NSURL *outputURL = RNSTCacheFileURL(cacheDirectory, cacheKey, kind, format);
      NSError *writeError = nil;
      if (![encodedImage writeToURL:outputURL options:NSDataWritingAtomic error:&writeError]) {
        [self finishOperation:operation
                              result:nil
                      rejectionCode:RNSTCacheError
                             message:@"Unable to write the generated thumbnail to the cache."
                     underlyingError:writeError];
        return;
      }
      if ([self isOperationCancelled:operation]) {
        [self finishCancelledOperation:operation];
        return;
      }

      [self finishOperation:operation
                            result:@{
                              @"uri" : outputURL.absoluteString,
                              @"width" : @((NSInteger)pixelWidth),
                              @"height" : @((NSInteger)pixelHeight),
                              @"kind" : kind,
                              @"mimeType" : RNSTMIMETypeForFormat(format),
                              @"fromCache" : @NO,
                            }
                    rejectionCode:nil
                           message:nil
                   underlyingError:nil];
    });
  }];
}

- (void)generate:(NSString *)requestId
             uri:(NSString *)uri
           width:(NSInteger)width
          height:(NSInteger)height
          format:(NSString *)format
         quality:(double)quality
        useCache:(BOOL)useCache
allowIconFallback:(BOOL)allowIconFallback
         resolve:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject
{
  if ([requestId stringByTrimmingCharactersInSet:
          NSCharacterSet.whitespaceAndNewlineCharacterSet].length == 0) {
    dispatch_async(self.workQueue, ^{
      RNSTReject(reject, RNSTInvalidArgument, @"requestId must not be empty.", nil);
    });
    return;
  }

  RNSTThumbnailOperation *operation = [[RNSTThumbnailOperation alloc] init];
  operation.requestId = [requestId copy];
  operation.resolve = resolve;
  operation.reject = reject;

  [self.stateLock lock];
  BOOL invalidated = self.invalidated;
  BOOL duplicateRequestId = self.operations[requestId] != nil;
  if (!invalidated && !duplicateRequestId) {
    self.operations[requestId] = operation;
  }
  [self.stateLock unlock];

  if (invalidated) {
    dispatch_async(self.workQueue, ^{
      RNSTReject(reject, RNSTCancelled, @"The native module has been invalidated.", nil);
    });
    return;
  }

  if (duplicateRequestId) {
    dispatch_async(self.workQueue, ^{
      RNSTReject(
          reject,
          RNSTInvalidArgument,
          @"requestId must be unique while a thumbnail request is active.",
          nil);
    });
    return;
  }

  dispatch_async(self.workQueue, ^{
    [self startOperation:operation
                     uri:uri
                   width:width
                  height:height
                  format:format
                 quality:quality
                useCache:useCache
       allowIconFallback:allowIconFallback];
  });
}

- (void)cancel:(NSString *)requestId
{
  [self.stateLock lock];
  RNSTThumbnailOperation *operation = self.operations[requestId];
  if (operation != nil && !operation.finished) {
    operation.cancelled = YES;
  }
  [self.stateLock unlock];

  if (operation == nil) {
    return;
  }

  dispatch_async(self.workQueue, ^{
    [self.stateLock lock];
    BOOL finished = operation.finished;
    QLThumbnailGenerationRequest *request = operation.thumbnailRequest;
    [self.stateLock unlock];

    if (finished) {
      return;
    }
    if (request != nil) {
      // The completion handler is documented to run after cancellation; keeping the
      // operation alive until then also keeps any security-scoped access balanced.
      [QLThumbnailGenerator.sharedGenerator cancelRequest:request];
    } else {
      [self finishCancelledOperation:operation];
    }
  });
}

- (void)clearCache:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject
{
  [self.stateLock lock];
  BOOL invalidated = self.invalidated;
  [self.stateLock unlock];
  if (invalidated) {
    dispatch_async(self.workQueue, ^{
      RNSTReject(reject, RNSTCacheError, @"The native module has been invalidated.", nil);
    });
    return;
  }

  dispatch_async(self.workQueue, ^{
    NSURL *cachesURL = [[NSFileManager defaultManager] URLsForDirectory:NSCachesDirectory
                                                              inDomains:NSUserDomainMask]
                           .firstObject;
    NSURL *directoryURL = [cachesURL URLByAppendingPathComponent:
        @"react-native-system-thumbnails" isDirectory:YES];
    if (cachesURL == nil || directoryURL == nil ||
        ![[NSFileManager defaultManager] fileExistsAtPath:directoryURL.path]) {
      resolve(@0);
      return;
    }

    NSError *listingError = nil;
    NSArray<NSURL *> *contents = [[NSFileManager defaultManager]
        contentsOfDirectoryAtURL:directoryURL
      includingPropertiesForKeys:nil
                         options:NSDirectoryEnumerationSkipsSubdirectoryDescendants
                           error:&listingError];
    if (contents == nil) {
      RNSTReject(reject, RNSTCacheError, @"Unable to read the thumbnail cache.", listingError);
      return;
    }

    NSInteger removedCount = 0;
    NSError *firstRemovalError = nil;
    for (NSURL *itemURL in contents) {
      NSError *removeError = nil;
      if ([[NSFileManager defaultManager] removeItemAtURL:itemURL error:&removeError]) {
        if (removedCount < INT32_MAX) {
          removedCount++;
        }
      } else if (firstRemovalError == nil) {
        firstRemovalError = removeError;
      }
    }

    if (firstRemovalError != nil) {
      RNSTReject(
          reject,
          RNSTCacheError,
          @"One or more cached thumbnails could not be removed.",
          firstRemovalError);
      return;
    }
    resolve(@(removedCount));
  });
}

- (void)invalidate
{
  [self.stateLock lock];
  if (self.invalidated) {
    [self.stateLock unlock];
    return;
  }
  self.invalidated = YES;
  NSArray<RNSTThumbnailOperation *> *activeOperations = self.operations.allValues;
  for (RNSTThumbnailOperation *operation in activeOperations) {
    operation.cancelled = YES;
  }
  [self.stateLock unlock];

  dispatch_async(self.workQueue, ^{
    for (RNSTThumbnailOperation *operation in activeOperations) {
      [self.stateLock lock];
      BOOL finished = operation.finished;
      QLThumbnailGenerationRequest *request = operation.thumbnailRequest;
      [self.stateLock unlock];
      if (finished) {
        continue;
      }
      if (request != nil) {
        [QLThumbnailGenerator.sharedGenerator cancelRequest:request];
      } else {
        [self finishCancelledOperation:operation];
      }
    }
  });
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeReactNativeSystemThumbnailsSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"ReactNativeSystemThumbnails";
}

@end
