#!/usr/bin/env python3
"""Deploy a Vite build to S3 and invalidate its CloudFront distribution."""

from __future__ import annotations

import mimetypes
import os
import sys
import time
from pathlib import Path

import boto3


def required_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"{name} repository variable is required")
    return value


def upload_args(path: Path) -> dict[str, str]:
    content_type, content_encoding = mimetypes.guess_type(path.name)
    args = {
        "ContentType": content_type or "application/octet-stream",
        "CacheControl": (
            "public, max-age=31536000, immutable"
            if "assets" in path.parts
            else "no-cache, no-store, must-revalidate"
        ),
    }
    if content_encoding:
        args["ContentEncoding"] = content_encoding
    return args


def delete_stale_objects(s3, bucket: str, local_keys: set[str]) -> int:
    remote_keys: set[str] = set()
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket):
        remote_keys.update(item["Key"] for item in page.get("Contents", []))

    stale_keys = sorted(remote_keys - local_keys)
    for offset in range(0, len(stale_keys), 1_000):
        batch = stale_keys[offset : offset + 1_000]
        response = s3.delete_objects(
            Bucket=bucket,
            Delete={"Objects": [{"Key": key} for key in batch], "Quiet": True},
        )
        if errors := response.get("Errors"):
            raise RuntimeError(f"S3 object deletion failed: {errors}")
    return len(stale_keys)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: deploy_to_aws.py BUILD_DIRECTORY")

    build_directory = Path(sys.argv[1]).resolve()
    index_file = build_directory / "index.html"
    if not index_file.is_file():
        raise SystemExit(f"index.html not found in build directory: {build_directory}")

    bucket = required_env("FRONTEND_S3_BUCKET")
    distribution_id = required_env("CLOUDFRONT_DISTRIBUTION_ID")
    region = os.environ.get("AWS_REGION", "ap-northeast-2")

    sts = boto3.client("sts", region_name=region)
    s3 = boto3.client("s3", region_name=region)
    cloudfront = boto3.client("cloudfront", region_name="us-east-1")

    identity = sts.get_caller_identity()
    print(f"AWS identity: {identity['Arn']}")

    files = sorted(path for path in build_directory.rglob("*") if path.is_file())
    local_keys = {path.relative_to(build_directory).as_posix() for path in files}
    if not local_keys:
        raise SystemExit("refusing to deploy an empty build directory")

    # Upload index.html last so it never refers to assets that have not been uploaded yet.
    for path in [item for item in files if item != index_file] + [index_file]:
        key = path.relative_to(build_directory).as_posix()
        s3.upload_file(str(path), bucket, key, ExtraArgs=upload_args(path))
    print(f"Uploaded {len(files)} files to s3://{bucket}")

    deleted_count = delete_stale_objects(s3, bucket, local_keys)
    print(f"Deleted {deleted_count} stale S3 objects")

    caller_reference = f"{os.environ.get('GITHUB_RUN_ID', 'manual')}-{time.time_ns()}"
    response = cloudfront.create_invalidation(
        DistributionId=distribution_id,
        InvalidationBatch={
            "Paths": {"Quantity": 1, "Items": ["/*"]},
            "CallerReference": caller_reference,
        },
    )
    invalidation_id = response["Invalidation"]["Id"]
    print(f"Waiting for CloudFront invalidation {invalidation_id}")
    cloudfront.get_waiter("invalidation_completed").wait(
        DistributionId=distribution_id,
        Id=invalidation_id,
    )
    print("CloudFront invalidation completed")


if __name__ == "__main__":
    main()
