import streamlit as st
import pandas as pd
import plotly.express as px
import time

# --- PAGE CONFIG ---
st.set_page_config(page_title="Machine Ops & Costing", layout="wide")
# --- CONFIGURATION ---
# We use the 'export' endpoint, not the 'edit' endpoint
SHEET_ID = "1S8ECq792lFD4dsfhUiHpK1pycx_uEZePi_qlj3ZpQ_c"
SHEET_GID = "109996351" # e.g., "154826493" (Found in browser URL when on activity_raw tab)

SHEET_CSV_URL = f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=csv&gid={SHEET_GID}"

# --- 2. SIDEBAR (The "Wafeq" Controls) ---
st.sidebar.header("💰 Rate Card Configuration")
st.sidebar.write("Set your billing rates to calculate daily value.")

rate_m1 = st.sidebar.number_input("Machine 1 Rate (PKR/hr)", value=5000, step=500)
rate_m2 = st.sidebar.number_input("Machine 2 Rate (PKR/hr)", value=3500, step=500)

# Filter for "Billable" activities
billable_activities = st.sidebar.multiselect(
    "Billable Activities",
    ["Running", "Setup", "Maintenance", "Idle"],
    default=["Running", "Setup"]
)

# --- 3. DATA LOADER ---
@st.cache_data(ttl=60)
def load_data():
    try:
        df = pd.read_csv(SHEET_CSV_URL)
        # Clean headers
        df.columns = df.columns.str.strip().str.lower().str.replace(' ', '_')
        
        # Robust Time Parsing
        df['start_time'] = pd.to_datetime(df['start_time'], dayfirst=True, format='mixed', errors='coerce')
        df['end_time'] = pd.to_datetime(df['end_time'], dayfirst=True, format='mixed', errors='coerce')

        # Drop bad rows
        df = df.dropna(subset=['start_time', 'end_time'])

        # Data Enrichment
        # Ensure machine_id is string and handle duplicates/formatting
        df['machine_id'] = df['machine_id'].astype(str).str.replace('Machine ', '', regex=False)
        df['machine_label'] = "Machine " + df['machine_id'] 
        
        # Calculate Duration
        df['duration_hrs'] = (df['end_time'] - df['start_time']).dt.total_seconds() / 3600
        df['duration_min'] = df['duration_hrs'] * 60
        
        return df
    except Exception as e:
        st.error(f"Data Error: {e}")
        return pd.DataFrame()

# --- 4. MAIN DASHBOARD ---
df = load_data()

if not df.empty:
    st.title("🏭 Manufacturing Analytics")
    
    # --- COST CALCULATION ENGINE ---
    def calculate_cost(row):
        # 1. Check if activity is billable
        if row['activity_type'] not in billable_activities:
            return 0.0
        
        # 2. Apply Rate based on Machine ID
        if '1' in str(row['machine_id']):
            return row['duration_hrs'] * rate_m1
        elif '2' in str(row['machine_id']):
            return row['duration_hrs'] * rate_m2
        return 0.0

    df['cost_pkr'] = df.apply(calculate_cost, axis=1)

    # --- TOP METRICS ---
    total_rev = df['cost_pkr'].sum()
    total_hrs = df[df['cost_pkr'] > 0]['duration_hrs'].sum()
    
    m1, m2, m3 = st.columns(3)
    m1.metric("💰 Total Revenue (Est)", f"PKR {total_rev:,.0f}")
    m2.metric("⏱️ Billable Hours", f"{total_hrs:.1f} hrs")
    m3.metric("📊 Efficiency (OEE)", "Tracking...") 

    st.divider()

    # --- TIMELINE VISUALIZATION ---
    st.subheader("Activity Timeline")
    fig = px.timeline(
        df, 
        x_start="start_time", 
        x_end="end_time", 
        y="machine_label",
        color="activity_type",
        # HOVER INFO
        hover_data={"remark": True, "submitted_by": True, "cost_pkr": ':.0f', "machine_label": False},
        color_discrete_map={
            "Running": "#2ecc71", "Idle": "#f1c40f", 
            "Setup": "#3498db", "Breakdown": "#e74c3c", "Off": "#95a5a6"
        }
    )
    fig.update_yaxes(autorange="reversed")
    st.plotly_chart(fig, use_container_width=True)

    # --- DETAILED COST TABLE ---
    with st.expander("View Financial Breakdown"):
        # Filter purely for the table view
        billable_df = df[df['cost_pkr'] > 0][['timestamp', 'machine_label', 'activity_type', 'duration_min', 'cost_pkr', 'remark']]
        st.dataframe(billable_df.style.format({"cost_pkr": "PKR {:.2f}", "duration_min": "{:.1f} min"}))

else:
    st.info("Waiting for data... Please submit a form entry.")
