#!/usr/bin/env python3
"""
Генератор QR-кодов: золото на чёрном.
Использование: python3 gen-qr.py <url> [output.png]
"""
import sys
import qrcode
from PIL import Image, ImageDraw, ImageFont

GOLD = "#D4AF37"
BLACK = "#111111"

def gen(url, output=None, label=None):
    if not output:
        # Имя файла из URL
        slug = url.rstrip('/').split('/')[-1]
        output = f"{slug}.png"

    qr = qrcode.QRCode(
        version=2,
        error_correction=qrcode.constants.ERROR_CORRECT_H,
        box_size=12,
        border=3,
    )
    qr.add_data(url)
    qr.make(fit=True)

    qr_img = qr.make_image(fill_color=GOLD, back_color=BLACK).convert("RGB")
    qr_w, qr_h = qr_img.size

    pad = 30
    label_h = 70 if label else 20
    card_w = qr_w + pad * 2
    card_h = qr_h + pad * 2 + label_h

    card = Image.new("RGB", (card_w, card_h), BLACK)
    card.paste(qr_img, (pad, pad))

    if label:
        draw = ImageDraw.Draw(card)
        line_y = pad + qr_h + 10
        draw.line([(pad, line_y), (card_w - pad, line_y)], fill=GOLD, width=2)

        try:
            font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 28)
        except:
            font = ImageFont.load_default()

        bbox = draw.textbbox((0, 0), label, font=font)
        tw = bbox[2] - bbox[0]
        draw.text(((card_w - tw) // 2, line_y + 12), label, fill=GOLD, font=font)

    card.save(output, quality=95)
    print(f"✅ {output}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 gen-qr.py <url> [output.png] [label]")
        print("Example: python3 gen-qr.py https://karmarent.app/api/qr/KR_001 qr.png 'KARMA RENT'")
        sys.exit(1)

    url = sys.argv[1]
    output = sys.argv[2] if len(sys.argv) > 2 else None
    label = sys.argv[3] if len(sys.argv) > 3 else None
    gen(url, output, label)
