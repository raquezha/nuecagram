import os
import math
from PIL import Image, ImageDraw, ImageFont

TARGET_W, TARGET_H = 800, 680
BG_COLOR = (14, 22, 33)

def draw_clean_lottie_loading(base_img, progress, loading_text):
    """Renders 100% accurate WebApp loading screen preserving Telegram window frame and rounded box boundaries."""
    frame = base_img.copy()
    draw = ImageDraw.Draw(frame, 'RGBA')

    # Fill ONLY the inner WebApp viewport box [31, 56, 468, 745] with WebApp dark background (23, 33, 43)
    # This preserves the Telegram window frame, side margins (X<31, X>468), and top/bottom bars
    draw.rectangle([31, 56, 468, 745], fill=(23, 33, 43))

    # Lottie container centered in viewport
    cx, cy = 250, 340
    scale = 0.52

    # Lottie paperplane floating bobbing & tilt
    bob_y = math.sin(progress * 2 * math.pi) * 12
    tilt_deg = math.sin(progress * 2 * math.pi) * 10 - 4

    rad = math.radians(tilt_deg)
    cos_a, sin_a = math.cos(rad), math.sin(rad)

    def tf(x, y):
        sx, sy = x * scale, y * scale
        rx = sx * cos_a - sy * sin_a
        ry = sx * sin_a + sy * cos_a
        return (cx + rx, cy + bob_y + ry)

    # 1. Trailing motion particles matching Lottie theme
    particles = [
        (progress, -90, -35, 4.5, (255, 234, 254, 230)),
        ((progress + 0.3) % 1.0, -50, 40, 3.5, (255, 234, 254, 190)),
        ((progress + 0.6) % 1.0, 80, -30, 5.0, (255, 234, 254, 210)),
        ((progress + 0.8) % 1.0, 100, 35, 3.0, (255, 234, 254, 170)),
        ((progress + 0.45) % 1.0, -110, 10, 2.5, (255, 234, 254, 150)),
    ]
    for p_prog, base_offset_x, offset_y, r, color in particles:
        dx = base_offset_x - ((p_prog * 50) % 70)
        dy = offset_y + math.sin(p_prog * math.pi * 2) * 8
        pt = tf(dx, dy)
        draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=color)

    # 2. Paperplane vector shapes matching src/main/resources/webapp/loading.json
    # Group 1 (Shadow/Inner Fold): offset (-30.38, 19.17)
    g1_raw = [[18.967, -3.189], [-18.967, 19.935], [-0.949, -19.935]]
    g1_pts = [tf(-30.38 + p[0] * 0.5, 19.17 + p[1] * 0.5) for p in g1_raw]
    draw.polygon(g1_pts, fill=(129, 0, 56, 255))

    # Group 2 (Bottom/Side Wing): offset (9.30, -3.26)
    g2_raw = [[-98.335, 64.79], [-105.619, 4.984], [105.619, -64.79], [-80.316, 24.919]]
    g2_pts = [tf(9.30 + p[0] * 0.5, -3.26 + p[1] * 0.5) for p in g2_raw]
    draw.polygon(g2_pts, fill=(209, 10, 96, 255))

    # Group 3 (Main Top Wing): offset (0, 0)
    g3_raw = [[-133.812, -42.171], [133.812, -75.141], [5.765, 75.141], [-61.708, 18.402], [124.227, -71.307], [-87.011, -1.534]]
    g3_pts = [tf(p[0] * 0.5, p[1] * 0.5) for p in g3_raw]
    draw.polygon(g3_pts, fill=(255, 17, 121, 255))

    # 3. Rotating quips on spinner-label below Lottie container (Y=465)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 14)
    except Exception:
        font = ImageFont.load_default()

    bbox = draw.textbbox((0, 0), loading_text, font=font)
    tx = (500 - (bbox[2] - bbox[0])) // 2
    ty = 465
    draw.text((tx, ty), loading_text, fill=(172, 179, 188, 255), font=font)

    return frame


def draw_cursor_and_ripple(img, cursor_x, cursor_y, is_clicking=False, ripple_progress=0.0, is_pressing=False):
    """Draws mouse cursor and smooth expanding ripple animation."""
    res = img.copy()
    draw = ImageDraw.Draw(res, 'RGBA')

    # Draw expanding ripple effect during mouse click
    if is_clicking and ripple_progress > 0:
        max_r = 26.0
        curr_r = max_r * (1.0 - (1.0 - ripple_progress) ** 2) # Ease-out expansion
        alpha = int(240 * (1.0 - ripple_progress ** 1.3))     # Smooth fade-out

        if curr_r > 1 and alpha > 0:
            # Outer ring
            ring_w = max(1, int(3.5 - 2.5 * ripple_progress))
            draw.ellipse([cursor_x - curr_r, cursor_y - curr_r,
                          cursor_x + curr_r, cursor_y + curr_r],
                         outline=(100, 181, 246, alpha), width=ring_w)

            # Soft glow outer ring
            glow_r = curr_r + 2.5
            glow_alpha = int(alpha * 0.45)
            draw.ellipse([cursor_x - glow_r, cursor_y - glow_r,
                          cursor_x + glow_r, cursor_y + glow_r],
                         outline=(147, 197, 253, glow_alpha), width=1)

            # Inner core pulse
            inner_r = max(2.0, curr_r * 0.4)
            inner_alpha = int(alpha * 0.65)
            draw.ellipse([cursor_x - inner_r, cursor_y - inner_r,
                          cursor_x + inner_r, cursor_y + inner_r],
                         fill=(100, 181, 246, inner_alpha))

    # Mouse cursor arrow shape
    pts = [(0, 0), (0, 19), (4, 15), (8, 22), (11, 20), (7, 13), (14, 13)]

    # Slightly shift down-right when pressing
    press_offset_x = 1.5 if is_pressing else 0.0
    press_offset_y = 1.5 if is_pressing else 0.0

    shadow_pts = [(cursor_x + p[0] + 1.5 + press_offset_x, cursor_y + p[1] + 2.5 + press_offset_y) for p in pts]
    body_pts = [(cursor_x + p[0] + press_offset_x, cursor_y + p[1] + press_offset_y) for p in pts]

    draw.polygon(shadow_pts, fill=(0, 0, 0, 110))
    draw.polygon(body_pts, fill=(255, 255, 255, 255), outline=(20, 25, 35, 255))
    return res


def fit_on_canvas(img):
    """Fits image inside canvas with dark background."""
    max_w = TARGET_W - 40
    max_h = TARGET_H - 40
    img_copy = img.copy()
    img_copy.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (TARGET_W, TARGET_H), BG_COLOR)
    x = (TARGET_W - img_copy.width) // 2
    y = (TARGET_H - img_copy.height) // 2
    canvas.paste(img_copy, (x, y), img_copy)
    return canvas


def ease_in_out_cubic(t):
    return 4 * t * t * t if t < 0.5 else 1 - math.pow(-2 * t + 2, 3) / 2


def generate_movement_frames(base_img, start_pos, end_pos, num_frames=10, frame_dur=45):
    """Generates smooth interpolated cursor movement frames with cubic easing."""
    frames = []
    durations = []
    x0, y0 = start_pos
    x1, y1 = end_pos

    for i in range(1, num_frames + 1):
        t = i / num_frames
        eased_t = ease_in_out_cubic(t)
        cx = x0 + (x1 - x0) * eased_t
        cy = y0 + (y1 - y0) * eased_t

        frame = draw_cursor_and_ripple(base_img, cx, cy, is_clicking=False)
        canvas = fit_on_canvas(frame)
        frames.append(canvas)
        durations.append(frame_dur)

    return frames, durations


def generate_click_sequence(base_img, pos, pre_hold=2, click_frames=6, post_hold=2, frame_dur=50):
    """Generates ultra smooth click sequence (press down + expanding/fading ripple ring)."""
    frames = []
    durations = []
    cx, cy = pos

    # Pre-click hover
    for _ in range(pre_hold):
        f = draw_cursor_and_ripple(base_img, cx, cy, is_clicking=False)
        frames.append(fit_on_canvas(f))
        durations.append(frame_dur)

    # Click & expanding ripple animation
    for i in range(click_frames):
        p = (i + 1) / click_frames
        is_press = i < 2 # Press down on initial frames
        f = draw_cursor_and_ripple(base_img, cx, cy, is_clicking=True, ripple_progress=p, is_pressing=is_press)
        frames.append(fit_on_canvas(f))
        durations.append(frame_dur)

    # Post-click hold
    for _ in range(post_hold):
        f = draw_cursor_and_ripple(base_img, cx, cy, is_clicking=False)
        frames.append(fit_on_canvas(f))
        durations.append(frame_dur)

    return frames, durations


def build_gif():
    # Base images
    load_base = Image.open('docs/assets/preview-webapp-list.png').convert('RGBA')
    list_img = Image.open('docs/assets/preview-webapp-list.png').convert('RGBA')
    detail_img = Image.open('docs/assets/preview-webapp-detail.png').convert('RGBA')
    chat_img = Image.open('docs/assets/telegram-chat.png').convert('RGBA')
    reveal_img = Image.open('docs/assets/preview-webapp-reveal.png').convert('RGBA')
    admin_img = Image.open('docs/assets/admin-dashboard.png').convert('RGBA')

    all_frames = []
    all_durations = []

    # ==========================================
    # Scene 1: Lottie WebApp Loading State
    # ==========================================
    quips = [
        "Preparing WebApp session...",
        "Syncing Telegram destinations...",
        "Connecting to Nuecagram backend...",
        "Loading repository status..."
    ]

    total_lottie_frames = 24 # ~1.8 seconds smooth animation
    lottie_dur_per_frame = 75 # ms

    for i in range(total_lottie_frames):
        prog = i / total_lottie_frames
        quip = quips[(i // 6) % len(quips)]
        f = draw_clean_lottie_loading(load_base, prog, quip)
        all_frames.append(fit_on_canvas(f))
        all_durations.append(lottie_dur_per_frame)

    # ==========================================
    # Scene 2: Connected Repositories List
    # ==========================================
    # Move from offscreen (420, 720) to nuecagram-core card (250, 275)
    f_move, d_move = generate_movement_frames(list_img, (420, 720), (250, 275), num_frames=10, frame_dur=45)
    all_frames.extend(f_move)
    all_durations.extend(d_move)

    # Click on nuecagram-core card
    f_click, d_click = generate_click_sequence(list_img, (250, 275), pre_hold=2, click_frames=6, post_hold=2, frame_dur=50)
    all_frames.extend(f_click)
    all_durations.extend(d_click)

    # ==========================================
    # Scene 3: Repository Detail View
    # ==========================================
    # Move from (250, 275) to "Test Alert" button (130, 550)
    f_move, d_move = generate_movement_frames(detail_img, (250, 275), (130, 550), num_frames=10, frame_dur=45)
    all_frames.extend(f_move)
    all_durations.extend(d_move)

    # Click "Test Alert"
    f_click, d_click = generate_click_sequence(detail_img, (130, 550), pre_hold=2, click_frames=6, post_hold=3, frame_dur=50)
    all_frames.extend(f_click)
    all_durations.extend(d_click)

    # ==========================================
    # Scene 4: Telegram Chat Live Notifications
    # ==========================================
    # Initial display without cursor for notification arrival effect
    all_frames.append(fit_on_canvas(chat_img))
    all_durations.append(800)

    # Move from (350, 750) to "OPEN" WebApp button (80, 715)
    f_move, d_move = generate_movement_frames(chat_img, (350, 750), (80, 715), num_frames=9, frame_dur=45)
    all_frames.extend(f_move)
    all_durations.extend(d_move)

    # Click "OPEN" button
    f_click, d_click = generate_click_sequence(chat_img, (80, 715), pre_hold=2, click_frames=6, post_hold=2, frame_dur=50)
    all_frames.extend(f_click)
    all_durations.extend(d_click)

    # ==========================================
    # Scene 5: Webhook URL & Secret Token Reveal
    # ==========================================
    # Reveal modal static preview
    all_frames.append(fit_on_canvas(reveal_img))
    all_durations.append(400)

    # Move to "Copy Secret Token" button at (385, 400)
    f_move, d_move = generate_movement_frames(reveal_img, (80, 715), (385, 400), num_frames=10, frame_dur=45)
    all_frames.extend(f_move)
    all_durations.extend(d_move)

    # Click "Copy Secret Token"
    f_click, d_click = generate_click_sequence(reveal_img, (385, 400), pre_hold=2, click_frames=6, post_hold=2, frame_dur=50)
    all_frames.extend(f_click)
    all_durations.extend(d_click)

    # Move to "Done" button at (250, 630)
    f_move, d_move = generate_movement_frames(reveal_img, (385, 400), (250, 630), num_frames=9, frame_dur=45)
    all_frames.extend(f_move)
    all_durations.extend(d_move)

    # Click "Done"
    f_click, d_click = generate_click_sequence(reveal_img, (250, 630), pre_hold=2, click_frames=6, post_hold=2, frame_dur=50)
    all_frames.extend(f_click)
    all_durations.extend(d_click)

    # ==========================================
    # Scene 6: Platform Admin Dashboard Overview
    # ==========================================
    all_frames.append(fit_on_canvas(admin_img))
    all_durations.append(2400)

    # Quantize each frame to 256 colors MEDIANCUT
    quantized_frames = []
    for f in all_frames:
        q = f.convert('RGB').quantize(colors=256, method=Image.Quantize.MEDIANCUT)
        quantized_frames.append(q)

    gif_path = 'docs/assets/demo.gif'
    quantized_frames[0].save(
        gif_path,
        save_all=True,
        append_images=quantized_frames[1:],
        duration=all_durations,
        loop=0,
        optimize=True
    )

    size_kb = os.path.getsize(gif_path) / 1024
    print(f"Interactive animated GIF built successfully: {gif_path} ({size_kb:.1f} KB, {len(quantized_frames)} frames)")

if __name__ == "__main__":
    build_gif()
