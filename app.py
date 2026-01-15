import streamlit as st
import pandas as pd
import plotly.express as px
import time

# --- PAGE CONFIG ---
st.set_page_config(page_title="Machine Ops Feed", layout="wide")

# --- CONFIGURATION ---
# We use the 'export' endpoint, not the 'edit' endpoint
SHEET_ID = "1S8ECq792lFD4dsfhUiHpK1pycx_uEZePi_qlj3ZpQ_c"
SHEET_GID = "109996351" # e.g., "154826493" (Found in browser URL when on activity_raw tab)

SHEET_CSV_URL = f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=csv&gid={SHEET_GID}"

@st.cache_data(ttl=60)
def load_data():
    try:
        df = pd.read_csv(SHEET_CSV_URL)
        
        # 1. Clean Headers
        df.columns = df.columns.str.strip().str.lower().str.replace(' ', '_')
        
        # 2. Convert Times (ROBUST FIX)
        # dayfirst=True tells it that 15/01 is Jan 15th, not Month 15 (Error)
        # format='mixed' allows it to handle different formats in the same column
        df['start_time'] = pd.to_datetime(df['start_time'], dayfirst=True, format='mixed', errors='coerce')
        df['end_time'] = pd.to_datetime(df['end_time'], dayfirst=True, format='mixed', errors='coerce')

        # 3. Clean up Machine Names
        # Ensure it's a string so it plots correctly
        df['machine_id'] = "Machine " + df['machine_id'].astype(str)
        
        # 4. Calculate Duration
        df['duration_min'] = (df['end_time'] - df['start_time']).dt.total_seconds() / 60
        df['duration_min'] = df['duration_min'].round(1)
        
        return df
        
    except Exception as e:
        st.error(f"Error loading data: {e}")
        return pd.DataFrame()

# --- APP LAYOUT ---
st.title("🏭 Real-Time Manufacturing Feed")
st.write("Live view of machine activity from operator logs.")

# Reload Button
if st.button('🔄 Refresh Data'):
    st.cache_data.clear()

# Load Data
df = load_data()

if not df.empty:
    # --- METRICS ROW ---
    # Calculate simple stats for the header
    total_jobs = len(df)
    last_activity = df['timestamp'].max()
    
    col1, col2, col3 = st.columns(3)
    col1.metric("Total Jobs Logged", total_jobs)
    col2.metric("Last Update", str(last_activity)[11:19]) # Show time only
    col3.metric("Active Machines", df['machine_id'].nunique())

    # --- THE TIMELINE CHART ---
    fig = px.timeline(
        df, 
        x_start="start_time", 
        x_end="end_time", 
        y="machine_id",
        color="activity_type",
        hover_data=["remark", "submitted_by", "duration_min"],
        color_discrete_map={
            "Running": "#2ecc71",      
            "Idle": "#f1c40f",         
            "Setup": "#3498db",        
            "Maintenance": "#e67e22",  
            "Breakdown": "#e74c3c",    
            "Off": "#95a5a6"           
        }
    )
    
    fig.update_yaxes(autorange="reversed")
    fig.update_layout(height=400, template="plotly_white")
    
    # Render Plotly in Streamlit
    st.plotly_chart(fig, use_container_width=True)

    # --- RAW DATA TABLE (Optional) ---
    with st.expander("View Raw Data Logs"):
        st.dataframe(df)

else:
    st.warning("No data found yet. Please check the Google Sheet link.")
