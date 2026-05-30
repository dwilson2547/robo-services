#!/usr/bin/env python3
"""Trace a racetrack boundary polygon from satellite imagery using seed points
or a hand-drawn linestring as a spatial guide.

Fetches Esri World Imagery tiles (no API key required), segments the image
by color/texture to isolate the track surface, and outputs a GeoJSON polygon.

Usage (seed points):
    python trace.py --seeds "lat,lon" "lat,lon" [--output track.geojson]

Usage (linestring guide — more accurate, recommended):
    python trace.py --linestring drawn_line.geojson [--corridor-width 30] \
        [--output track.geojson]

Example (Utah Motorsports Campus main circuit):
    python trace.py \\
        --seeds "40.606,-112.523" "40.609,-112.528" "40.614,-112.521" \\
        --output utah_motorsports_main.geojson
"""
from __future__ import annotations

import argparse
import io
import json
import math
import sys
from pathlib import Path

import cv2
import mercantile
import numpy as np
import requests
from PIL import Image
from shapely.geometry import mapping
from shapely.ops import unary_union
from shapely.geometry import Polygon as ShapelyPolygon

ESRI_TILE_URL = (
    "https://server.arcgisonline.com/ArcGIS/rest/services/"
    "World_Imagery/MapServer/tile/{z}/{y}/{x}"
)
TILE_SIZE = 256
HEADERS = {"User-Agent": "track-poly-poc/0.1.0"}


# ── Tile fetching and stitching ───────────────────────────────────────────────

def fetch_tiles(
    seeds: list[tuple[float, float]],
    zoom: int = 17,
    buffer_tiles: int = 3,
) -> tuple[np.ndarray, dict]:
    """Fetch and stitch Esri World Imagery tiles around the seed points.

    Returns (image_rgb, geotransform) where geotransform maps pixel coords to
    (lon, lat) via:
        lon = gt["lon_origin"] + col * gt["lon_per_pixel"]
        lat = gt["lat_origin"] + row * gt["lat_per_pixel"]
    """
    # Find the tile range covering all seeds + buffer
    tile_xs, tile_ys = [], []
    for lat, lon in seeds:
        tile = mercantile.tile(lon, lat, zoom)
        tile_xs.append(tile.x)
        tile_ys.append(tile.y)

    min_tx = min(tile_xs) - buffer_tiles
    max_tx = max(tile_xs) + buffer_tiles
    min_ty = min(tile_ys) - buffer_tiles
    max_ty = max(tile_ys) + buffer_tiles

    cols = max_tx - min_tx + 1
    rows = max_ty - min_ty + 1
    canvas = np.zeros((rows * TILE_SIZE, cols * TILE_SIZE, 3), dtype=np.uint8)

    print(f"  Fetching {cols * rows} tiles at zoom {zoom}…")
    for ty in range(min_ty, max_ty + 1):
        for tx in range(min_tx, max_tx + 1):
            url = ESRI_TILE_URL.format(z=zoom, y=ty, x=tx)
            resp = requests.get(url, headers=HEADERS, timeout=30)
            resp.raise_for_status()
            tile_img = np.array(Image.open(io.BytesIO(resp.content)).convert("RGB"))
            row_off = (ty - min_ty) * TILE_SIZE
            col_off = (tx - min_tx) * TILE_SIZE
            canvas[row_off:row_off + TILE_SIZE, col_off:col_off + TILE_SIZE] = tile_img

    # Build geotransform using the bounds of the top-left tile
    top_left_bounds = mercantile.bounds(min_tx, min_ty, zoom)
    bot_right_bounds = mercantile.bounds(max_tx, max_ty, zoom)

    total_lon = bot_right_bounds.east - top_left_bounds.west
    total_lat = top_left_bounds.north - bot_right_bounds.south

    total_px_w = cols * TILE_SIZE
    total_px_h = rows * TILE_SIZE

    geotransform = {
        "lon_origin": top_left_bounds.west,
        "lat_origin": top_left_bounds.north,
        "lon_per_pixel": total_lon / total_px_w,
        "lat_per_pixel": -total_lat / total_px_h,  # negative: rows increase downward
    }
    return canvas, geotransform


# ── Coordinate conversion ─────────────────────────────────────────────────────

def latlon_to_pixel(lat: float, lon: float, gt: dict) -> tuple[int, int]:
    col = int((lon - gt["lon_origin"]) / gt["lon_per_pixel"])
    row = int((lat - gt["lat_origin"]) / gt["lat_per_pixel"])
    return col, row


def pixel_to_latlon(col: int, row: int, gt: dict) -> tuple[float, float]:
    lon = gt["lon_origin"] + col * gt["lon_per_pixel"]
    lat = gt["lat_origin"] + row * gt["lat_per_pixel"]
    return lat, lon


# ── Linestring loading ────────────────────────────────────────────────────────

def load_linestring(path: str) -> list[tuple[float, float]]:
    """Load a GeoJSON LineString file and return (lat, lon) pairs.

    Accepts a FeatureCollection, Feature, or bare geometry.
    GeoJSON coordinates are [lon, lat]; we return (lat, lon) to match --seeds.
    """
    with open(path) as f:
        gj = json.load(f)

    if gj["type"] == "FeatureCollection":
        features = gj["features"]
    elif gj["type"] == "Feature":
        features = [gj]
    else:
        features = [{"geometry": gj}]

    for feat in features:
        geom = feat.get("geometry", feat)
        if geom and geom["type"] == "LineString":
            return [(lat, lon) for lon, lat in geom["coordinates"]]

    raise ValueError(f"No LineString geometry found in {path!r}")


# ── Corridor mask ─────────────────────────────────────────────────────────────

def build_corridor_mask(
    linestring_latlon: list[tuple[float, float]],
    gt: dict,
    image_shape: tuple,
    corridor_meters: float,
) -> np.ndarray:
    """Return a binary mask dilated around the linestring by corridor_meters.

    Constraining the flood-fill result to this mask prevents bleed into
    adjacent same-colored surfaces that are spatially separated from the track.
    """
    h, w = image_shape[:2]

    # Approximate meters-per-pixel at the mean latitude of the linestring
    avg_lat = sum(lat for lat, _ in linestring_latlon) / len(linestring_latlon)
    meters_per_lon_deg = 111_320 * math.cos(math.radians(avg_lat))
    meters_per_pixel = abs(gt["lon_per_pixel"]) * meters_per_lon_deg
    corridor_px = max(1, int(corridor_meters / meters_per_pixel))

    # Draw the linestring and dilate to form the corridor
    px_coords = np.array(
        [latlon_to_pixel(lat, lon, gt) for lat, lon in linestring_latlon],
        dtype=np.int32,
    )
    mask = np.zeros((h, w), dtype=np.uint8)
    cv2.polylines(mask, [px_coords], isClosed=False, color=255, thickness=1)

    k = 2 * corridor_px + 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    return cv2.dilate(mask, kernel, iterations=1)


# ── Segmentation ──────────────────────────────────────────────────────────────

def segment_track(
    image_rgb: np.ndarray,
    seed_pixels: list[tuple[int, int]],
    k: int = 6,
    corridor_mask: np.ndarray | None = None,
) -> np.ndarray:
    """Return a binary mask of the track surface connected to the seed pixels.

    Pipeline:
    1. K-means clustering in LAB colorspace to group surface types.
    2. Identify clusters containing seed pixels.
    3. Build binary mask from those clusters.
    4. Flood-fill to isolate connected region from each seed.
    5. Optionally mask to corridor_mask to prevent spatial bleed.
    6. Morphological close (fill small gaps) then open (remove noise spurs).
    """
    h, w = image_rgb.shape[:2]

    # K-means in LAB
    lab = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    flat = lab.reshape(-1, 3)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 0.5)
    _, labels, _ = cv2.kmeans(flat, k, None, criteria, 5, cv2.KMEANS_PP_CENTERS)
    label_map = labels.reshape(h, w).astype(np.uint8)

    # Find which cluster labels the seed pixels belong to
    seed_labels = set()
    for col, row in seed_pixels:
        if 0 <= row < h and 0 <= col < w:
            seed_labels.add(int(label_map[row, col]))

    if not seed_labels:
        raise ValueError("Seed pixels are outside the image bounds.")

    # Binary mask: pixels in seed clusters
    cluster_mask = np.zeros((h, w), dtype=np.uint8)
    for lbl in seed_labels:
        cluster_mask[label_map == lbl] = 255

    # Apply corridor constraint before flood-fill so growth can't escape
    if corridor_mask is not None:
        cluster_mask = cv2.bitwise_and(cluster_mask, corridor_mask)

    # Flood-fill from each seed to keep only connected components
    fill_mask = np.zeros((h, w), dtype=np.uint8)
    for col, row in seed_pixels:
        if 0 <= row < h and 0 <= col < w and cluster_mask[row, col] == 255:
            flood = cluster_mask.copy()
            flood_padded = np.zeros((h + 2, w + 2), dtype=np.uint8)
            cv2.floodFill(flood, flood_padded, (col, row), 128)
            fill_mask[flood == 128] = 255

    if fill_mask.sum() == 0:
        # Seeds not in a cluster-matching pixel — fall back to full cluster mask
        fill_mask = cluster_mask

    # Apply corridor mask again after fill (belt-and-suspenders)
    if corridor_mask is not None:
        fill_mask = cv2.bitwise_and(fill_mask, corridor_mask)

    # Morphological close (fill small gaps) then open (remove noise spurs)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    filled = cv2.morphologyEx(fill_mask, cv2.MORPH_CLOSE, kernel, iterations=3)
    cleaned = cv2.morphologyEx(filled, cv2.MORPH_OPEN, kernel, iterations=1)

    return cleaned


# ── Contour extraction ────────────────────────────────────────────────────────

def extract_polygon(mask: np.ndarray, seed_pixels: list[tuple[int, int]], gt: dict):
    """Extract the largest contour near the seeds as a Shapely polygon."""
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_TC89_KCOS)

    if not contours:
        raise ValueError("No contours found in segmentation mask.")

    # Pick the contour closest to the centroid of the seed pixels
    seed_cx = sum(c for c, _ in seed_pixels) / len(seed_pixels)
    seed_cy = sum(r for _, r in seed_pixels) / len(seed_pixels)

    def contour_score(c):
        M = cv2.moments(c)
        if M["m00"] == 0:
            return float("inf"), 0
        cx = M["m10"] / M["m00"]
        cy = M["m01"] / M["m00"]
        dist = math.hypot(cx - seed_cx, cy - seed_cy)
        area = cv2.contourArea(c)
        return dist, -area  # prefer close, then large

    contours = sorted(contours, key=contour_score)
    best = contours[0]

    # Simplify and convert pixel coords to lat/lon
    epsilon = 0.005 * cv2.arcLength(best, True)
    approx = cv2.approxPolyDP(best, epsilon, True)

    coords = []
    for pt in approx:
        col, row = int(pt[0][0]), int(pt[0][1])
        lat, lon = pixel_to_latlon(col, row, gt)
        coords.append((lon, lat))

    if len(coords) < 3:
        raise ValueError(f"Contour too small after simplification ({len(coords)} points).")

    return ShapelyPolygon(coords)


# ── Main ──────────────────────────────────────────────────────────────────────

def parse_seed(s: str) -> tuple[float, float]:
    parts = s.strip().split(",")
    if len(parts) != 2:
        raise argparse.ArgumentTypeError(f"Seeds must be 'lat,lon', got: {s!r}")
    return float(parts[0]), float(parts[1])


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Trace a racetrack boundary from satellite imagery."
    )
    seed_group = parser.add_mutually_exclusive_group(required=True)
    seed_group.add_argument(
        "--seeds",
        nargs="+",
        metavar="lat,lon",
        help='Two or more points on the track surface, e.g. "40.606,-112.523"',
    )
    seed_group.add_argument(
        "--linestring",
        metavar="FILE",
        help="GeoJSON file with a LineString tracing the track. Vertices are "
             "used as seeds and the line is buffered to constrain detection.",
    )
    parser.add_argument(
        "--corridor-width",
        type=float,
        default=30.0,
        metavar="METERS",
        help="Half-width of the detection corridor around the linestring in "
             "metres (default: 30). Only used with --linestring.",
    )
    parser.add_argument(
        "--output",
        default="traced_track.geojson",
        help="Output GeoJSON file (default: traced_track.geojson)",
    )
    parser.add_argument(
        "--zoom",
        type=int,
        default=17,
        help="Tile zoom level (default: 17, ~1m/pixel). Use 16 for larger tracks.",
    )
    parser.add_argument(
        "--clusters",
        type=int,
        default=6,
        help="K-means cluster count (default: 6). Increase if track isn't isolated.",
    )
    parser.add_argument(
        "--buffer",
        type=int,
        default=None,
        help="Extra tiles to fetch around the bounding box "
             "(default: 1 for --linestring, 3 for --seeds).",
    )
    args = parser.parse_args()

    output_path = Path(args.output)
    use_linestring = args.linestring is not None

    if use_linestring:
        print(f"\n  Loading linestring: {args.linestring}")
        seeds = load_linestring(args.linestring)
        print(f"  {len(seeds)} linestring vertices → using as seeds")
        buffer_tiles = args.buffer if args.buffer is not None else 1
    else:
        seeds = [parse_seed(s) for s in args.seeds]
        buffer_tiles = args.buffer if args.buffer is not None else 3

    print(f"  Zoom: {args.zoom}  |  Clusters: {args.clusters}  |  Buffer: {buffer_tiles} tiles")
    if use_linestring:
        print(f"  Corridor width: {args.corridor_width}m")

    print("\n[1/4] Fetching satellite tiles…")
    image, gt = fetch_tiles(seeds, zoom=args.zoom, buffer_tiles=buffer_tiles)
    print(f"  Image size: {image.shape[1]} × {image.shape[0]} px")

    print("\n[2/4] Converting seed points to pixel coordinates…")
    seed_pixels = [latlon_to_pixel(lat, lon, gt) for lat, lon in seeds]

    corridor_mask = None
    if use_linestring:
        print(f"  Building {args.corridor_width}m corridor mask…")
        corridor_mask = build_corridor_mask(seeds, gt, image.shape, args.corridor_width)
        covered_px = corridor_mask.sum() // 255
        total_px = image.shape[0] * image.shape[1]
        print(f"  Corridor covers {covered_px:,} px ({100 * covered_px / total_px:.1f}% of image)")
    else:
        for (lat, lon), (col, row) in zip(seeds, seed_pixels):
            print(f"  ({lat}, {lon}) → pixel ({col}, {row})")

    print("\n[3/4] Segmenting track surface…")
    mask = segment_track(image, seed_pixels, k=args.clusters, corridor_mask=corridor_mask)
    coverage_pct = 100 * mask.sum() / 255 / mask.size
    print(f"  Track mask coverage: {coverage_pct:.1f}% of image")

    print("\n[4/4] Extracting polygon…")
    polygon = extract_polygon(mask, seed_pixels, gt)
    bounds = polygon.bounds
    print(f"  Geometry: {polygon.geom_type}, {len(polygon.exterior.coords)} vertices")
    print(f"  Bounding box:")
    print(f"    lon {bounds[0]:.6f} → {bounds[2]:.6f}")
    print(f"    lat {bounds[1]:.6f} → {bounds[3]:.6f}")

    props: dict = {
        "name": output_path.stem,
        "source": "esri_world_imagery_trace",
        "zoom": args.zoom,
    }
    if use_linestring:
        props["linestring_file"] = args.linestring
        props["corridor_width_m"] = args.corridor_width
    else:
        props["seeds"] = [list(s) for s in seeds]

    geojson = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": props,
                "geometry": mapping(polygon),
            }
        ],
    }
    output_path.write_text(json.dumps(geojson, indent=2))
    print(f"\n  Saved → {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
